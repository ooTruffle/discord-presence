package com.ootruffle.discordpresence;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.ootruffle.discordpresence.ipc.Presence;
import com.ootruffle.discordpresence.ipc.PresenceClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fabric client adapter for Minecraft 1.21.1 through 26.3. */
public final class DiscordPresenceMod implements ClientModInitializer {
    private static final String PREFIX = "§9[Discord] §r";
    private static final Pattern PLAYERS = Pattern.compile("Players:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern BW_TEAM = Pattern.compile("^(.*?)\\s*([✓✔✗✘✖]|\\d+)$");
    private static final Pattern BW_JUNK = Pattern.compile("[^\\x20-\\x7E✓✔✗✘✖]");
    private static DiscordPresenceMod instance;

    private PresenceConfig config;
    private PresenceClient client;
    private Level lastLevel;
    private HypixelLocation location;
    private String lastLocationKey;
    private int tick;
    private int locrawAtTick = -1;
    private int locrawTries;
    private long hideLocrawUntil;
    private long sessionStart = System.currentTimeMillis();
    private long lastActivity = System.currentTimeMillis();
    private long worldChangeMs;
    private Object lastScreen;
    private double lastMouseX;
    private double lastMouseY;
    private float lastYaw;
    private float lastPitch;
    private boolean wasInWorld;
    private boolean apiAvailable;
    private long apiLocationMs;

    @Override
    public void onInitializeClient() {
        instance = this;
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        config = new PresenceConfig(new File(configDir, "discordpresence.properties"));
        config.load();
        client = PresenceClient.start();
        applyConfig();

        String version = FabricLoader.getInstance().getModContainer("discordpresence")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1");
        HypixelNames.load(new File(configDir, "discordpresence-hypixel-games.json"), version);

        if (FabricLoader.getInstance().isModLoaded("oneconfig")) {
            try {
                OneConfigSupport.init(this);
            } catch (Throwable t) {
                System.err.println("[DiscordPresence] OneConfig page could not be created: " + t);
            }
        }
        if (FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) {
            try {
                HypixelModApiSupport.init(this);
                apiAvailable = true;
            } catch (Throwable t) {
                System.err.println("[DiscordPresence] Hypixel Mod API unavailable; using /locraw: " + t);
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> onJoin(mc));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> onChat(message.getString()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rpc")
                    .executes(ctx -> runCommand(""))
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("arguments", StringArgumentType.greedyString())
                            .executes(ctx -> runCommand(StringArgumentType.getString(ctx, "arguments")))));
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("discordpresence")
                    .executes(ctx -> runCommand(""))
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("arguments", StringArgumentType.greedyString())
                            .executes(ctx -> runCommand(StringArgumentType.getString(ctx, "arguments")))));
        });
    }

    public static DiscordPresenceMod get() { return instance; }
    public PresenceConfig config() { return config; }

    public void applyConfig() {
        if (client != null) client.configure(config.appId, config.enabled);
    }

    public void reconnect() {
        applyConfig();
        if (client != null) client.reconnect();
    }

    private void onJoin(Minecraft mc) {
        location = null;
        lastLocationKey = null;
        locrawTries = 0;
        worldChangeMs = System.currentTimeMillis();
        locrawAtTick = onHypixel(mc) ? tick + (apiAvailable ? 100 : 30) : -1;
    }

    private void onTick(Minecraft mc) {
        tick++;
        boolean inWorld = mc.level != null;
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            if (inWorld != wasInWorld) sessionStart = System.currentTimeMillis();
            wasInWorld = inWorld;
            location = null;
            lastLocationKey = null;
            locrawTries = 0;
            worldChangeMs = System.currentTimeMillis();
            locrawAtTick = inWorld && onHypixel(mc) ? tick + (apiAvailable ? 100 : 30) : -1;
        }
        if (locrawAtTick >= 0 && tick >= locrawAtTick) {
            locrawAtTick = -1;
            requestLocraw(mc);
        }
        trackActivity(mc);
        if (tick % 20 == 0) client.update(build(mc));
    }

    private void requestLocraw(Minecraft mc) {
        if (mc.player == null || !onHypixel(mc) || locrawTries >= 2) return;
        if (apiAvailable && apiLocationMs >= worldChangeMs - 3000) return;
        locrawTries++;
        hideLocrawUntil = System.currentTimeMillis() + 5000;
        mc.player.connection.sendCommand("locraw");
        if (locrawTries < 2) locrawAtTick = tick + 120;
    }

    void onApiLocation(HypixelLocation newLocation) {
        location = newLocation;
        apiLocationMs = System.currentTimeMillis();
        locrawAtTick = -1;
    }

    private boolean onChat(String text) {
        HypixelLocation parsed = HypixelLocation.parse(text);
        if (parsed == null) return true;
        location = parsed;
        locrawAtTick = -1;
        boolean ours = System.currentTimeMillis() < hideLocrawUntil;
        if (ours) hideLocrawUntil = 0;
        return !ours;
    }

    private Presence build(Minecraft mc) {
        String name = mc.getUser().getName();
        String uuid = mc.getUser().getProfileId().toString().replace("-", "");
        String head = config.headUrl.isEmpty() ? null
                : config.headUrl.replace("{uuid}", uuid).replace("{name}", name);
        String details;
        String state = null;
        long start = sessionStart;
        int partySize = 0;
        int partyMax = 0;

        if (mc.level == null) {
            details = "In the menus";
        } else if (mc.hasSingleplayerServer()) {
            details = "Singleplayer";
            if (config.showWorldName && mc.getSingleplayerServer() != null) {
                state = mc.getSingleplayerServer().getWorldData().getLevelName();
            }
        } else if (onHypixel(mc)) {
            HypixelLocation loc = location;
            if (loc == null || !config.showGameInfo) {
                details = "Playing on Hypixel";
            } else if (loc.isLimbo()) {
                details = "Hypixel";
                state = "AFK in Limbo";
            } else {
                String mode = loc.modeName();
                details = mode == null ? loc.gameName() : loc.gameName() + " · " + mode;
                List<String> lines = sidebar(mc);
                boolean waiting = contains(lines, "Waiting") || contains(lines, "Starting in");
                boolean bedwarsGame = !loc.isLobby() && !waiting && "BEDWARS".equals(loc.gameType());
                BedwarsTeam team = bedwarsGame ? bedwarsTeam(lines) : null;
                int[] standing = bedwarsGame && team == null ? bedwarsStanding(lines) : null;
                if (standing != null && standing[0] > 0) {
                    team = standing[1] > 1 ? new BedwarsTeam("", false, 0, true) : BedwarsTeam.GAME_OVER;
                }
                if (loc.isLobby()) state = "MAIN".equalsIgnoreCase(loc.gameType()) ? "Hanging out" : "In the lobby";
                else if (waiting) state = "Waiting to start";
                else if (team == BedwarsTeam.GAME_OVER) state = "Game over";
                else if (isSpectating(mc) || (team != null && team.eliminated())) state = "Spectating";
                else if (team != null && config.showBedwarsTeam) state = team.describe();
                else state = "In game";
                if (config.showHypixelMap && loc.map() != null && !loc.isLobby()) state += " · " + loc.map();
                if (config.showPlayerCount && waiting) {
                    int[] count = playerCount(lines);
                    if (count != null) { partySize = count[0]; partyMax = count[1]; }
                }
                String key = loc.server() + "|" + loc.mode();
                if (!key.equals(lastLocationKey)) {
                    lastLocationKey = key;
                    sessionStart = start = System.currentTimeMillis();
                }
            }
        } else {
            details = "Multiplayer";
            ServerData server = mc.getCurrentServer();
            if (config.showServerIp && server != null && server.ip != null) state = "on " + stripPort(server.ip);
            else if (server == null) state = "LAN game";
        }

        if (isIdle()) state = state == null ? "Idle" : "Idle · " + state;
        if (!config.showElapsed) start = 0;
        String largeText = config.largeText.replace("{name}", name);
        String large = config.largeImage;
        String small = null;
        String smallText = null;
        String icon = config.smallIconImage();
        String iconText = config.smallIconText();
        if ("head".equalsIgnoreCase(large)) {
            large = config.showHead ? head : null;
            if (large != null) { small = icon; smallText = iconText; }
            else if (icon != null) { large = icon; largeText = iconText; }
        } else if (config.showHead) { small = head; smallText = name; }
        else if (icon != null) { small = icon; smallText = iconText; }

        return new Presence(details, state, start, large, large == null ? null : largeText,
                small, smallText, partySize, partyMax);
    }

    private static boolean onHypixel(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server == null || server.ip == null || mc.hasSingleplayerServer()) return false;
        String ip = stripPort(server.ip).toLowerCase(Locale.ROOT);
        return ip.equals("hypixel.net") || ip.endsWith(".hypixel.net") || ip.equals("hypixel.io") || ip.endsWith(".hypixel.io");
    }

    private static String stripPort(String address) {
        int colon = address.lastIndexOf(':');
        return colon > 0 && address.indexOf(':') == colon ? address.substring(0, colon) : address;
    }

    private boolean isIdle() {
        return config.idleMinutes > 0 && System.currentTimeMillis() - lastActivity > (long) (config.idleMinutes * 60000f);
    }

    private record BedwarsTeam(String team, boolean bed, int playersLeft, boolean eliminated) {
        private static final BedwarsTeam GAME_OVER = new BedwarsTeam("", false, 0, true);

        String describe() {
            if (bed) return team + " team · Bed alive";
            return team + " team · Bed broken" + (playersLeft > 1 ? " · " + playersLeft + " left" : "");
        }
    }

    private static List<String> sidebar(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        if (mc.level == null) return lines;
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return lines;
        Collection<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective);
        for (PlayerScoreEntry entry : entries) {
            String owner = entry.owner();
            if (owner == null || owner.startsWith("#")) continue;
            PlayerTeam team = scoreboard.getPlayersTeam(owner);
            lines.add(PlayerTeam.formatNameForTeam(team, Component.literal(owner)).getString());
        }
        return lines;
    }

    private static String clean(String raw) {
        return BW_JUNK.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private static boolean contains(List<String> lines, String value) {
        for (String line : lines) if (clean(line).contains(value)) return true;
        return false;
    }

    private static BedwarsTeam bedwarsTeam(List<String> lines) {
        for (String raw : lines) {
            String line = clean(raw);
            int you = line.lastIndexOf("YOU");
            if (you < 0) continue;
            Matcher matcher = BW_TEAM.matcher(line.substring(0, you).trim());
            if (!matcher.find()) continue;
            String[] words = matcher.group(1).replace(":", "").trim().split(" ");
            String team = words[words.length - 1];
            if (team.isEmpty() || !Character.isLetter(team.charAt(0))) continue;
            String status = matcher.group(2);
            char mark = status.charAt(0);
            if (mark == '✓' || mark == '✔') return new BedwarsTeam(team, true, 0, false);
            if (Character.isDigit(mark)) return new BedwarsTeam(team, false, Integer.parseInt(status), false);
            return new BedwarsTeam(team, false, 0, true);
        }
        return null;
    }

    private static int[] bedwarsStanding(List<String> lines) {
        int teams = 0, standing = 0;
        for (String raw : lines) {
            Matcher matcher = BW_TEAM.matcher(clean(raw));
            if (!matcher.find() || !matcher.group(1).trim().matches("[A-Z] [A-Z][a-z]+:")) continue;
            teams++;
            char mark = matcher.group(2).charAt(0);
            if (mark == '✓' || mark == '✔' || Character.isDigit(mark)) standing++;
        }
        return new int[]{teams, standing};
    }

    private static int[] playerCount(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = PLAYERS.matcher(clean(line));
            if (matcher.find()) return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
        return null;
    }

    private static boolean isSpectating(Minecraft mc) {
        return mc.player != null && (mc.player.isSpectator()
                || (mc.player.isInvisible() && mc.player.getAbilities().mayfly && !mc.player.isCreative()));
    }

    private void trackActivity(Minecraft mc) {
        boolean active = currentScreen(mc) != lastScreen;
        lastScreen = currentScreen(mc);
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return;
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(window, x, y);
        if (x[0] != lastMouseX || y[0] != lastMouseY) active = true;
        lastMouseX = x[0]; lastMouseY = y[0];
        if (mc.player != null) {
            if (mc.player.getYRot() != lastYaw || mc.player.getXRot() != lastPitch) active = true;
            lastYaw = mc.player.getYRot(); lastPitch = mc.player.getXRot();
        }
        for (int key = GLFW.GLFW_KEY_SPACE; !active && key <= GLFW.GLFW_KEY_LAST; key++) {
            if (GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS) active = true;
        }
        for (int button = GLFW.GLFW_MOUSE_BUTTON_1; !active && button <= GLFW.GLFW_MOUSE_BUTTON_LAST; button++) {
            if (GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS) active = true;
        }
        if (active) lastActivity = System.currentTimeMillis();
    }

    private int runCommand(String input) {
        String[] args = input.trim().isEmpty() ? new String[0] : input.trim().split(" +");
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String message;
        switch (sub) {
            case "appid" -> {
                if (args.length < 2 || !args[1].chars().allMatch(Character::isDigit)) message = "Usage: /rpc appid <application id>";
                else { config.appId = args[1]; saveAndApply(); message = "Application id set. Connecting..."; }
            }
            case "on", "off", "toggle" -> {
                config.enabled = sub.equals("toggle") ? !config.enabled : sub.equals("on");
                saveAndApply(); message = "Rich presence " + (config.enabled ? "enabled" : "disabled");
            }
            case "reconnect" -> { reconnect(); message = "Reconnecting to Discord..."; }
            case "image" -> {
                if (args.length < 2) message = "Usage: /rpc image <asset key | https://url | head>";
                else { config.largeImage = args[1]; saveAndApply(); message = "Large image: " + args[1]; }
            }
            case "icon" -> {
                if (args.length < 2) message = "Small icon: " + config.smallIcon + ". Usage: /rpc icon <oneclient|oneconfig|none|asset key|https://url>";
                else { config.smallIcon = args[1]; saveAndApply(); message = "Small icon: " + args[1]; }
            }
            case "headsource" -> {
                int index = args.length > 1 ? Arrays.asList(PresenceConfig.HEAD_SOURCE_NAMES).indexOf(args[1].toLowerCase(Locale.ROOT)) : -1;
                if (index < 0) {
                    int current = PresenceConfig.headSourceIndex(config.headUrl);
                    String source = current < PresenceConfig.HEAD_SOURCE_NAMES.length ? PresenceConfig.HEAD_SOURCE_NAMES[current] : "custom";
                    message = "Head source: " + source + ". Usage: /rpc headsource <minotar|vzge|nmsr|mcheads>";
                }
                else { config.headUrl = PresenceConfig.HEAD_SOURCES[index]; saveAndApply(); message = "Head source: " + PresenceConfig.HEAD_SOURCE_NAMES[index]; }
            }
            case "game", "ip", "world", "map", "players", "timer", "head", "bedwarsteam" -> {
                boolean value = args.length > 1
                        ? args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true")
                        : !getFlag(sub);
                setFlag(sub, value);
                saveAndApply();
                message = sub + ": " + (value ? "shown" : "hidden");
            }
            case "where" -> message = location == null ? "No Hypixel location yet." : location.toString();
            case "debug" -> {
                Minecraft mc = Minecraft.getInstance();
                List<String> lines = sidebar(mc);
                message = "Team: " + bedwarsTeam(lines) + ", spectating: " + isSpectating(mc) + ", idle: " + isIdle();
                System.out.println("[DiscordPresence] /rpc debug: location=" + location + ", " + message);
                for (String line : lines) System.out.println("[DiscordPresence] sidebar: " + line);
            }
            case "" -> message = "Status: " + client.status() + " · /rpc on|off|toggle, appid <id>, image, icon, headsource, reconnect, debug, where";
            default -> message = "Usage: /rpc on|off|toggle, appid <id>, image <key|url|head>, icon <name>, headsource <name>, reconnect, debug, where; game|ip|world|map|players|timer|head [on|off]";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) showMessage(Component.literal(PREFIX + message));
        else System.out.println("[DiscordPresence] " + message);
        return 1;
    }

    private boolean getFlag(String key) {
        return switch (key) {
            case "ip" -> config.showServerIp;
            case "world" -> config.showWorldName;
            case "map" -> config.showHypixelMap;
            case "players" -> config.showPlayerCount;
            case "timer" -> config.showElapsed;
            case "game" -> config.showGameInfo;
            case "bedwarsteam" -> config.showBedwarsTeam;
            default -> config.showHead;
        };
    }

    private void setFlag(String key, boolean value) {
        switch (key) {
            case "ip" -> config.showServerIp = value;
            case "world" -> config.showWorldName = value;
            case "map" -> config.showHypixelMap = value;
            case "players" -> config.showPlayerCount = value;
            case "timer" -> config.showElapsed = value;
            case "game" -> config.showGameInfo = value;
            case "bedwarsteam" -> config.showBedwarsTeam = value;
            default -> config.showHead = value;
        }
    }

    private void saveAndApply() { config.save(); applyConfig(); }

    private static Object currentScreen(Minecraft mc) {
        // The current screen moved into Gui in 26.2.
        //? if >= 26.2 {
        return mc.gui.screen();
        //?} else
        /*return mc.screen;*/
    }

    private static void showMessage(Component message) {
        // The chat moved into Gui's Hud in 26.2; client-system messages changed in 26.1.
        //? if >= 26.2 {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
        //?} elif >= 26.1 {
        /*Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
        *///?} else {
        /*Minecraft.getInstance().gui.getChat().addMessage(message);*/
        //?}
    }
}
