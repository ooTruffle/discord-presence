package com.ootruffle.discordpresence.ipc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal Discord RPC client speaking the local IPC protocol directly: a named pipe on
 * Windows, a unix domain socket everywhere else. Not thread-safe - {@link PresenceClient}
 * only ever touches it from its own IO thread.
 */
public final class DiscordIpc implements AutoCloseable {
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;

    private Pipe pipe;
    private int nonce;

    /** Tries discord-ipc-0 through discord-ipc-9 in every known location until one answers READY. */
    public void connect(String clientId) throws IOException {
        close();
        IOException last = null;
        for (int i = 0; i < 10 && pipe == null; i++) {
            for (Pipe p : candidates(i)) {
                try {
                    pipe = p;
                    send(OP_HANDSHAKE, handshake(clientId));
                    Frame reply = read();
                    if (reply.op == OP_CLOSE) {
                        throw new IOException("Discord refused the connection: " + message(reply.json));
                    }
                    if (!"READY".equals(str(reply.json, "evt"))) {
                        throw new IOException("unexpected handshake reply: " + reply.json);
                    }
                    return;
                } catch (IOException e) {
                    // A refusal (e.g. a bad application id) won't get better on another pipe.
                    if (e.getMessage() != null && e.getMessage().startsWith("Discord refused")) {
                        close();
                        throw e;
                    }
                    last = e;
                    close();
                }
            }
        }
        throw last != null ? last : new IOException("Discord is not running");
    }

    public boolean connected() {
        return pipe != null;
    }

    /**
     * Sends SET_ACTIVITY; {@code activity == null} clears the presence.
     *
     * @return Discord's error message if it rejected the activity, otherwise null
     */
    public String setActivity(JsonObject activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) {
            args.add("activity", activity);
        }
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", String.valueOf(++nonce));
        send(OP_FRAME, cmd);

        Frame reply = read();
        if (reply.op == OP_CLOSE) {
            close();
            throw new IOException("Discord closed the connection: " + message(reply.json));
        }
        if ("ERROR".equals(str(reply.json, "evt"))) {
            return message(reply.json);
        }
        return null;
    }

    @Override
    public void close() {
        if (pipe != null) {
            pipe.close();
            pipe = null;
        }
    }

    private void send(int op, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(op).putInt(body.length).put(body);
        pipe.write(buf.array());
    }

    private Frame read() throws IOException {
        byte[] header = new byte[8];
        pipe.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int op = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1 << 20) {
            throw new IOException("bad frame length " + len);
        }
        byte[] body = new byte[len];
        pipe.readFully(body);
        JsonObject json;
        try {
            json = new JsonParser().parse(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("bad JSON from Discord", e);
        }
        return new Frame(op, json);
    }

    private static JsonObject handshake(String clientId) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("client_id", clientId);
        return o;
    }

    private static String message(JsonObject json) {
        if (json.has("data") && json.get("data").isJsonObject()) {
            String m = str(json.getAsJsonObject("data"), "message");
            if (m != null) {
                return m;
            }
        }
        String m = str(json, "message");
        return m != null ? m : json.toString();
    }

    private static String str(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    /** Every place discord-ipc-{index} may live, opened lazily so a missing one costs nothing. */
    private static List<Pipe> candidates(int index) {
        List<Pipe> out = new ArrayList<>();
        String name = "discord-ipc-" + index;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            out.add(lazy(() -> windows("\\\\.\\pipe\\" + name)));
            return out;
        }

        List<String> roots = new ArrayList<>();
        for (String v : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String env = System.getenv(v);
            if (env != null && !env.isEmpty()) {
                roots.add(env);
            }
        }
        roots.add("/tmp");
        // Regular installs, then Flatpak, Snap and Flatpak Vesktop sandboxes.
        for (String root : roots) {
            for (String sub : new String[]{"", "app/com.discordapp.Discord/", "snap.discord/", ".flatpak/dev.vencord.Vesktop/xdg-run/"}) {
                File f = new File(root, sub + name);
                if (f.exists()) {
                    out.add(lazy(() -> unix(f.getPath())));
                }
            }
        }
        return out;
    }

    private static Pipe lazy(Opener opener) {
        return new Pipe() {
            Pipe real;

            Pipe get() throws IOException {
                if (real == null) {
                    real = opener.open();
                }
                return real;
            }

            @Override
            public void write(byte[] d) throws IOException {
                get().write(d);
            }

            @Override
            public void readFully(byte[] d) throws IOException {
                get().readFully(d);
            }

            @Override
            public void close() {
                if (real != null) {
                    real.close();
                }
            }
        };
    }

    private static Pipe windows(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "rw");
        return new Pipe() {
            @Override
            public void write(byte[] d) throws IOException {
                raf.write(d);
            }

            @Override
            public void readFully(byte[] d) throws IOException {
                raf.readFully(d);
            }

            @Override
            public void close() {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    private static Pipe unix(String path) throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.connect(UnixDomainSocketAddress.of(path));
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return new Pipe() {
            @Override
            public void write(byte[] d) throws IOException {
                ByteBuffer b = ByteBuffer.wrap(d);
                while (b.hasRemaining()) {
                    ch.write(b);
                }
            }

            @Override
            public void readFully(byte[] d) throws IOException {
                ByteBuffer b = ByteBuffer.wrap(d);
                while (b.hasRemaining()) {
                    if (ch.read(b) < 0) {
                        throw new IOException("Discord closed the pipe");
                    }
                }
            }

            @Override
            public void close() {
                try {
                    ch.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    private record Frame(int op, JsonObject json) {
    }

    private interface Opener {
        Pipe open() throws IOException;
    }

    private interface Pipe {
        void write(byte[] d) throws IOException;

        void readFully(byte[] d) throws IOException;

        void close();
    }
}
