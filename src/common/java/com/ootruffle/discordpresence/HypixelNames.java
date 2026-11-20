package com.ootruffle.discordpresence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Game and mode display names from Hypixel's public resources endpoint, cached on disk.
 * Used ahead of the built-in tables in {@link HypixelLocation} so new modes get proper
 * names without a mod update.
 */
public final class HypixelNames {
    private static final String URL_GAMES = "https://api.hypixel.net/v2/resources/games";
    private static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;

    private static volatile Map<String, String> games = Map.of();
    /** Keyed "GAMETYPE|MODE". */
    private static volatile Map<String, String> modes = Map.of();

    private HypixelNames() {
    }

    /** Loads the cache right away and refreshes it in the background when it is old. */
    public static void load(File cache, String version) {
        Thread t = new Thread(() -> {
            try {
                boolean cached = false;
                if (cache.isFile()) {
                    // A damaged cache must not stop the refresh below, or it would never be replaced.
                    try {
                        parse(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
                        cached = true;
                    } catch (Exception e) {
                        System.err.println("[DiscordPresence] ignoring unreadable " + cache + ": " + e);
                    }
                }
                if (!cached || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) {
                    String json = download(version);
                    parse(json);
                    Files.write(cache.toPath(), json.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable e) {
                System.err.println("[DiscordPresence] could not load Hypixel game names (using built-in names): " + e);
            }
        }, "DiscordPresence game names");
        t.setDaemon(true);
        t.start();
    }

    public static String game(String gameType) {
        return gameType == null ? null : games.get(gameType);
    }

    public static String mode(String gameType, String mode) {
        return gameType == null || mode == null ? null : modes.get(gameType + "|" + mode);
    }

    private static String download(String version) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_GAMES).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "DiscordPresence/" + version + " (Minecraft 1.8.9 mod)");
        if (c.getResponseCode() != 200) {
            throw new Exception("HTTP " + c.getResponseCode());
        }
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void parse(String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (!root.has("games") || !root.get("games").isJsonObject()) {
            throw new IllegalStateException("no games in reply");
        }
        Map<String, String> g = new HashMap<>();
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("games").entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject game = e.getValue().getAsJsonObject();
            if (game.has("name") && game.get("name").isJsonPrimitive()) {
                g.put(e.getKey(), game.get("name").getAsString());
            }
            if (game.has("modeNames") && game.get("modeNames").isJsonObject()) {
                for (Map.Entry<String, JsonElement> mode : game.getAsJsonObject("modeNames").entrySet()) {
                    if (mode.getValue().isJsonPrimitive()) {
                        m.put(e.getKey() + "|" + mode.getKey(), mode.getValue().getAsString());
                    }
                }
            }
        }
        games = g;
        modes = m;
    }
}
