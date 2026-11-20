package com.ootruffle.discordpresence.ipc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the Discord connection on a background thread. The game thread only ever calls
 * {@link #update}, {@link #configure} and {@link #reconnect}, which just set volatile fields,
 * so a hung or missing Discord can never stall a frame.
 */
public final class PresenceClient implements Runnable {
    /** Discord rate-limits SET_ACTIVITY to 5 per 20 seconds. */
    private static final long MIN_INTERVAL_MS = 4000L;
    private static final long RETRY_MS = 15000L;
    private static final long TIMEOUT_MS = 10000L;
    /** Pipe calls that timed out and are still blocked; past this we stop opening new ones. */
    private static final int MAX_STUCK = 3;

    // Pipe reads can block forever, so every pipe call runs on its own daemon thread.
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "DiscordPresence pipe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger stuck = new AtomicInteger();

    private volatile DiscordIpc ipc = new DiscordIpc();
    private volatile Presence wanted;
    private volatile String appId = "";
    private volatile boolean enabled = true;
    private volatile boolean reconnect;
    private volatile String status = "starting";

    // Only touched from the client thread.
    private Presence sent;
    private String connectedAppId;
    private long lastSend;
    private volatile long nextRetry;

    public static PresenceClient start() {
        PresenceClient c = new PresenceClient();
        Thread t = new Thread(c, "DiscordPresence IPC");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> c.ipc.close(), "DiscordPresence shutdown"));
        return c;
    }

    /** {@code null} clears the presence. */
    public void update(Presence p) {
        wanted = p;
    }

    public void configure(String appId, boolean enabled) {
        this.appId = appId == null ? "" : appId.trim();
        this.enabled = enabled;
    }

    public void reconnect() {
        reconnect = true;
        nextRetry = 0;
    }

    public String status() {
        return status;
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(500);
                tick();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                setStatus("error: " + t);
                abandon();
                nextRetry = System.currentTimeMillis() + RETRY_MS;
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        String id = appId;
        if (!enabled || id.isEmpty() || !id.chars().allMatch(Character::isDigit)) {
            if (ipc.connected()) {
                abandon();
            }
            sent = null;
            setStatus(!enabled ? "disabled" : "no application id set (/rpc appid <id>)");
            return;
        }

        if (reconnect || (ipc.connected() && !id.equals(connectedAppId))) {
            reconnect = false;
            abandon();
            sent = null;
        }

        if (!ipc.connected()) {
            if (now < nextRetry) {
                return;
            }
            if (stuck.get() >= MAX_STUCK) {
                nextRetry = now + RETRY_MS;
                setStatus("waiting for Discord to answer (try restarting Discord)");
                return;
            }
            DiscordIpc c = ipc;
            try {
                timed(() -> {
                    c.connect(id);
                    return null;
                });
                connectedAppId = id;
                sent = null;
                setStatus("connected");
            } catch (IOException e) {
                nextRetry = System.currentTimeMillis() + RETRY_MS;
                setStatus("not connected: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }
        }

        Presence p = wanted;
        if (Objects.equals(p, sent) || now - lastSend < MIN_INTERVAL_MS) {
            return;
        }
        DiscordIpc c = ipc;
        JsonObject activity = p == null ? null : toJson(p);
        try {
            String err = timed(() -> c.setActivity(activity));
            lastSend = now;
            sent = p;
            setStatus(err == null ? "connected" : "Discord rejected the presence: " + err);
        } catch (IOException e) {
            abandon();
            nextRetry = System.currentTimeMillis() + 2000;
            setStatus("connection lost: " + e.getMessage());
        }
    }

    /** Runs a pipe call with a timeout; a call that times out is left to finish (or not) on its own thread. */
    private <T> T timed(Callable<T> op) throws IOException {
        AtomicBoolean timedOut = new AtomicBoolean();
        Future<T> f = io.submit(() -> {
            try {
                return op.call();
            } finally {
                if (timedOut.get()) {
                    stuck.decrementAndGet();
                }
            }
        });
        try {
            return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (!f.isDone() && timedOut.compareAndSet(false, true)) {
                stuck.incrementAndGet();
            }
            abandon();
            throw new IOException("Discord didn't answer (a new Discord app can take a few minutes to start working). Retrying");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException(String.valueOf(cause), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** Drops the current connection without waiting on it: closing a blocked pipe can block too. */
    private void abandon() {
        DiscordIpc dead = ipc;
        ipc = new DiscordIpc();
        io.execute(dead::close);
    }

    private void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
            log(s);
        }
    }

    private static void log(String s) {
        try {
            LogManager.getLogger("DiscordPresence").info(s);
        } catch (Throwable t) {
            System.out.println("[DiscordPresence] " + s);
        }
    }

    private static JsonObject toJson(Presence p) {
        JsonObject a = new JsonObject();
        put(a, "details", p.details());
        put(a, "state", p.state());
        if (p.start() > 0) {
            JsonObject ts = new JsonObject();
            ts.addProperty("start", p.start());
            a.add("timestamps", ts);
        }
        JsonObject assets = new JsonObject();
        put(assets, "large_image", p.largeImage());
        put(assets, "large_text", p.largeText());
        put(assets, "small_image", p.smallImage());
        put(assets, "small_text", p.smallText());
        if (assets.entrySet().size() > 0) {
            a.add("assets", assets);
        }
        if (p.partyMax() > 0 && p.partySize() > 0 && p.partySize() <= p.partyMax()) {
            JsonObject party = new JsonObject();
            JsonArray size = new JsonArray();
            size.add(new JsonPrimitive(p.partySize()));
            size.add(new JsonPrimitive(p.partyMax()));
            party.add("size", size);
            a.add("party", party);
        }
        return a;
    }

    /** Discord rejects strings shorter than 2 or longer than 128 characters. */
    private static void put(JsonObject o, String key, String value) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return;
        }
        if (v.length() < 2) {
            v = v + " ";
        }
        if (v.length() > 128) {
            v = v.substring(0, 127) + "…";
        }
        o.addProperty(key, v);
    }
}
