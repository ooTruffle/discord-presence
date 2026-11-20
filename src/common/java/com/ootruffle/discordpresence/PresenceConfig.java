package com.ootruffle.discordpresence;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

/** Settings, stored as a plain properties file so they work with or without OneConfig. */
public final class PresenceConfig {
    public boolean enabled = true;
    /** Discord application id (from discord.com/developers/applications). */
    public String appId = "";
    public boolean showServerIp = true;
    public boolean showWorldName = true;
    public boolean showHypixelMap = true;
    public boolean showPlayerCount = true;
    public boolean showElapsed = true;
    public boolean showHead = true;
    public boolean showBedwarsTeam = true;
    /** Off: Hypixel shows only "Playing on Hypixel", with no game, mode, map or team. */
    public boolean showGameInfo = true;
    /** Minutes without input before the presence switches to "Idle"; 0 turns it off. */
    public float idleMinutes = 5.0f;
    /** An uploaded asset key, an https:// image url, or "head" for the player's head. */
    public String largeImage = "head";
    /** Hover text of the large image; {name} becomes the player's name. */
    public String largeText = "{name}";

    public static final String[] HEAD_SOURCES = {
            "https://minotar.net/helm/{uuid}/128.png",
            "https://vzge.me/face/128/{uuid}.png",
            "https://nmsr.nickac.dev/face/{uuid}",
            "https://mc-heads.net/avatar/{uuid}/128",
    };
    public static final String[] HEAD_SOURCE_NAMES = {"minotar", "vzge", "nmsr", "mcheads"};
    public static final String DEFAULT_HEAD_URL = HEAD_SOURCES[0];
    /** The default before minotar; configs still holding it are moved to the new default. */
    private static final String OLD_HEAD_URL = HEAD_SOURCES[3];
    /** Head image url used for largeImage=head; {uuid} and {name} are filled in. */
    public String headUrl = DEFAULT_HEAD_URL;

    /** "oneclient", "oneconfig", "none", an asset key or an https:// url. */
    public String smallIcon = "oneclient";
    public String smallIconText = "";
    public static final String[] SMALL_ICONS = {"oneclient", "oneconfig", "none"};
    private static final String ONECLIENT_ICON = "https://cdn.modrinth.com/data/QhX1nx34/687225dfdcd2784be2b7dbce34a5b699e5087525_96.webp";
    private static final String ONECONFIG_ICON = "https://cdn.modrinth.com/data/AibBIVmj/073a92ecc8bb5650456758f1a58990801de64db5_96.webp";

    /** Called after every save, so the OneConfig page can follow changes made with /rpc. */
    public Runnable onSave;

    private final File file;

    public String smallIconImage() {
        return switch (smallIcon.toLowerCase(Locale.ROOT)) {
            case "oneclient" -> ONECLIENT_ICON;
            case "oneconfig" -> ONECONFIG_ICON;
            case "none", "" -> null;
            default -> smallIcon;
        };
    }

    public String smallIconText() {
        return switch (smallIcon.toLowerCase(Locale.ROOT)) {
            case "oneclient" -> "OneClient";
            case "oneconfig" -> "OneConfig";
            default -> smallIconText.isEmpty() ? null : smallIconText;
        };
    }

    /** Index into {@link #SMALL_ICONS}, or its length for a custom key/url. */
    public static int smallIconIndex(String value) {
        for (int i = 0; i < SMALL_ICONS.length; i++) {
            if (SMALL_ICONS[i].equalsIgnoreCase(value)) {
                return i;
            }
        }
        return SMALL_ICONS.length;
    }

    /** Index into {@link #HEAD_SOURCES}, or its length for a custom url. */
    public static int headSourceIndex(String url) {
        for (int i = 0; i < HEAD_SOURCES.length; i++) {
            if (HEAD_SOURCES[i].equals(url)) {
                return i;
            }
        }
        return HEAD_SOURCES.length;
    }

    public PresenceConfig(File file) {
        this.file = file;
    }

    public void load() {
        if (!file.isFile()) {
            save();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file.toPath())) {
            p.load(in);
        } catch (Exception e) {
            System.err.println("[DiscordPresence] could not read " + file + ": " + e);
        }
        enabled = bool(p, "enabled", enabled);
        appId = p.getProperty("appId", appId).trim();
        showServerIp = bool(p, "showServerIp", showServerIp);
        showWorldName = bool(p, "showWorldName", showWorldName);
        showHypixelMap = bool(p, "showHypixelMap", showHypixelMap);
        showPlayerCount = bool(p, "showPlayerCount", showPlayerCount);
        showElapsed = bool(p, "showElapsed", showElapsed);
        showHead = bool(p, "showHead", showHead);
        showBedwarsTeam = bool(p, "showBedwarsTeam", showBedwarsTeam);
        showGameInfo = bool(p, "showGameInfo", showGameInfo);
        try {
            idleMinutes = Math.max(0, Float.parseFloat(p.getProperty("idleMinutes", String.valueOf(idleMinutes)).trim()));
        } catch (NumberFormatException ignored) {
        }
        largeImage = p.getProperty("largeImage", largeImage).trim();
        largeText = p.getProperty("largeText", largeText).trim();
        headUrl = p.getProperty("headUrl", headUrl).trim();
        if (headUrl.equals(OLD_HEAD_URL)) {
            headUrl = DEFAULT_HEAD_URL;
        }
        smallIcon = p.getProperty("smallIcon", smallIcon).trim();
        smallIconText = p.getProperty("smallIconText", smallIconText).trim();
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(enabled));
        p.setProperty("appId", appId);
        p.setProperty("showServerIp", String.valueOf(showServerIp));
        p.setProperty("showWorldName", String.valueOf(showWorldName));
        p.setProperty("showHypixelMap", String.valueOf(showHypixelMap));
        p.setProperty("showPlayerCount", String.valueOf(showPlayerCount));
        p.setProperty("showElapsed", String.valueOf(showElapsed));
        p.setProperty("showHead", String.valueOf(showHead));
        p.setProperty("showBedwarsTeam", String.valueOf(showBedwarsTeam));
        p.setProperty("showGameInfo", String.valueOf(showGameInfo));
        p.setProperty("idleMinutes", String.valueOf(idleMinutes));
        p.setProperty("largeImage", largeImage);
        p.setProperty("largeText", largeText);
        p.setProperty("headUrl", headUrl);
        p.setProperty("smallIcon", smallIcon);
        p.setProperty("smallIconText", smallIconText);
        if (onSave != null) {
            onSave.run();
        }
        try {
            file.getParentFile().mkdirs();
            try (OutputStream out = Files.newOutputStream(file.toPath())) {
                p.store(out, "Discord Presence settings (/rpc in chat)");
            }
        } catch (Exception e) {
            System.err.println("[DiscordPresence] could not save " + file + ": " + e);
        }
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }
}
