package com.ootruffle.discordpresence;

import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.annotations.Button;
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;
import org.polyfrost.oneconfig.api.config.v1.annotations.Text;

import net.minecraft.client.Minecraft;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * OneConfig settings page. {@link PresenceConfig} stays the source of truth: every change on
 * the page is copied into it and saved, and changes made with /rpc are copied back here.
 * Only loaded when OneConfig is installed.
 */
public class OneConfigSupport extends Config {
    private static OneConfigSupport page;
    private static DiscordPresenceMod mod;
    /** Set while a page change is being written to PresenceConfig, so its onSave doesn't echo back. */
    private static boolean applying;

    @Switch(title = "Enabled", description = "Show what you're playing on your Discord profile.", category = "General")
    public static boolean enabled = true;

    @Text(title = "Application ID", description = "From discord.com/developers/applications. The app's name is shown as \"Playing <name>\".", category = "General")
    public static String appId = "";

    @Text(title = "Large image", description = "Art asset key, an https:// image link, or \"head\" for your skin's face.", category = "Look")
    public static String largeImage = "head";

    @Text(title = "Large image hover text", description = "{name} is replaced with your username.", category = "Look")
    public static String largeText = "{name}";

    @Switch(title = "Show your head", description = "Your skin's face (as the large image, or a small badge).", category = "Look")
    public static boolean showHead = true;

    @Dropdown(title = "Head image source", description = "Where your head picture comes from. Switch if your head looks wrong. Custom = the headUrl line in config/discordpresence.properties.",
            options = {"Minotar", "Vzge", "NMSR", "mc-heads", "Custom"}, category = "Look")
    public static int headSource = 0;

    @Dropdown(title = "Small icon", description = "Badge in the corner of the big picture. Custom = the smallIcon line in config/discordpresence.properties (asset key or https:// link).",
            options = {"OneClient", "OneConfig", "None", "Custom"}, category = "Look")
    public static int smallIcon = 0;

    @Switch(title = "Elapsed timer", description = "Time spent in the current game, lobby or world.", category = "Look")
    public static boolean showElapsed = true;

    @Switch(title = "Server address", description = "Show the server you're on (not Hypixel, which shows the game).", category = "Privacy")
    public static boolean showServerIp = true;

    @Switch(title = "Singleplayer world name", category = "Privacy")
    public static boolean showWorldName = true;

    @Switch(title = "Show game info", description = "Off: just \"Playing on Hypixel\", without the game, mode, map, team or player count.", category = "Hypixel")
    public static boolean showGameInfo = true;

    @Switch(title = "Hypixel map", category = "Hypixel")
    public static boolean showHypixelMap = true;

    @Switch(title = "Bed Wars team & bed", description = "Shows e.g. \"Red team · Bed alive\" during a Bed Wars game.", category = "Hypixel")
    public static boolean showBedwarsTeam = true;

    @Slider(title = "Idle after (minutes)", description = "Shows \"Idle\" when you haven't touched the game for this long. 0 = off.", category = "General",
            min = 0, max = 30, step = 1)
    public static float idleMinutes = 5.0f;

    @Switch(title = "Player count while waiting", description = "Shows e.g. (6 of 8) before a game starts.", category = "Hypixel")
    public static boolean showPlayerCount = true;

    @Button(title = "Reconnect", text = "Reconnect", description = "Try connecting to Discord again now.", category = "General")
    public static void reconnect() {
        mod.reconnect();
    }

    public OneConfigSupport() {
        super("discordpresence.json", "Discord Presence", Category.UTILITY);
        PresenceConfig c = mod.config();
        this.<Boolean>on("enabled", v -> c.enabled = v);
        // Text boxes report every keystroke; wait until typing stops (see onText).
        this.<String>onText("appId", v -> c.appId = v.trim());
        this.<String>onText("largeImage", v -> c.largeImage = v.trim());
        this.<String>onText("largeText", v -> c.largeText = v);
        this.<Boolean>on("showHead", v -> c.showHead = v);
        this.<Integer>on("smallIcon", v -> {
            if (v >= 0 && v < PresenceConfig.SMALL_ICONS.length) {
                c.smallIcon = PresenceConfig.SMALL_ICONS[v];
            }
        });
        this.<Integer>on("headSource", v -> {
            if (v >= 0 && v < PresenceConfig.HEAD_SOURCES.length) {
                c.headUrl = PresenceConfig.HEAD_SOURCES[v];
            }
        });
        this.<Boolean>on("showElapsed", v -> c.showElapsed = v);
        this.<Boolean>on("showServerIp", v -> c.showServerIp = v);
        this.<Boolean>on("showWorldName", v -> c.showWorldName = v);
        this.<Boolean>on("showHypixelMap", v -> c.showHypixelMap = v);
        this.<Boolean>on("showPlayerCount", v -> c.showPlayerCount = v);
        this.<Boolean>on("showBedwarsTeam", v -> c.showBedwarsTeam = v);
        this.<Boolean>on("showGameInfo", v -> c.showGameInfo = v);
        this.<Float>on("idleMinutes", v -> c.idleMinutes = Math.max(0, v));
    }

    interface Apply<T> {
        void apply(T value);
    }

    private <T> void on(String field, Apply<T> apply) {
        Predicate<T> cb = v -> {
            if (v == null) {
                return false;
            }
            applying = true;
            try {
                apply.apply(v);
                mod.config().save();
                mod.applyConfig();
            } finally {
                applying = false;
            }
            return false;
        };
        addCallback(field, cb);
    }

    /** How long a text box must be left alone before its value is used. */
    private static final long TEXT_DELAY_MS = 1000;
    /** Text box changes not yet applied, by field; only touched on the client thread. */
    private static final Map<String, Runnable> pendingText = new LinkedHashMap<>();
    private static long pendingTextAt;
    private static Object pendingScreen;

    /**
     * Like {@link #on}, but for text boxes: applying every keystroke would rewrite the config
     * file each time and, for the application id, try to connect with every partial number.
     * The latest value is applied once typing has paused, or as soon as the page is closed.
     */
    private <T> void onText(String field, Apply<T> apply) {
        Predicate<T> cb = v -> {
            if (v == null) {
                return false;
            }
            pendingText.put(field, () -> apply.apply(v));
            pendingTextAt = System.currentTimeMillis();
            pendingScreen = Minecraft.getMinecraft().currentScreen;
            return false;
        };
        addCallback(field, cb);
    }

    private static void flushText(Minecraft mc) {
        if (pendingText.isEmpty()) {
            return;
        }
        if (mc.currentScreen == pendingScreen && System.currentTimeMillis() - pendingTextAt < TEXT_DELAY_MS) {
            return;
        }
        applying = true;
        try {
            pendingText.values().forEach(Runnable::run);
            pendingText.clear();
            mod.config().save();
            mod.applyConfig();
        } finally {
            applying = false;
        }
    }

    static void init(DiscordPresenceMod m) {
        mod = m;
        PresenceConfig c = m.config();
        pull(c);
        page = new OneConfigSupport();
        page.preload();
        // preload() reads OneConfig's own file; the properties file wins.
        pull(c);
        page.save();
        c.onSave = () -> {
            if (!applying && page != null) {
                pull(c);
                page.save();
            }
        };
        MinecraftClientEvents.TICK_END.register(OneConfigSupport::flushText);
    }

    private static void pull(PresenceConfig c) {
        enabled = c.enabled;
        appId = c.appId;
        largeImage = c.largeImage;
        largeText = c.largeText;
        showHead = c.showHead;
        headSource = PresenceConfig.headSourceIndex(c.headUrl);
        smallIcon = PresenceConfig.smallIconIndex(c.smallIcon);
        showElapsed = c.showElapsed;
        showServerIp = c.showServerIp;
        showWorldName = c.showWorldName;
        showHypixelMap = c.showHypixelMap;
        showPlayerCount = c.showPlayerCount;
        showBedwarsTeam = c.showBedwarsTeam;
        showGameInfo = c.showGameInfo;
        idleMinutes = c.idleMinutes;
    }
}
