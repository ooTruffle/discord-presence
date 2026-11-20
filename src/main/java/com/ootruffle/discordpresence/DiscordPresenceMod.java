package com.ootruffle.discordpresence;

import com.ootruffle.discordpresence.ipc.Presence;
import com.ootruffle.discordpresence.ipc.PresenceClient;
import com.ootruffle.discordpresence.ornithe.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.ornithemc.osl.entrypoints.api.ModInitializer;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordPresenceMod implements ModInitializer {
    private static final String P = EnumChatFormatting.BLUE + "[Discord] " + EnumChatFormatting.RESET;
    private static final Pattern PLAYERS = Pattern.compile("Players:\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** A Bed Wars team line: "R Red: ✓", "R Red: 2" or "R Red: ✗", optionally followed by "YOU". */
    private static final Pattern BW_TEAM = Pattern.compile("^(.*?)\\s*([✓✔✗✘✖]|\\d+)$");
    /** Hypixel pads sidebar lines with emoji and invisible characters; keep ASCII and the status marks. */
    private static final Pattern BW_JUNK = Pattern.compile("[^\\x20-\\x7E✓✔✗✘✖]");
    static final Logger LOG = LogManager.getLogger("DiscordPresence");

    /** Server whose sidebar was already logged because no team line was found on it. */
    private String dumpedSidebarFor;

    private static DiscordPresenceMod instance;

    private PresenceConfig config;
    private PresenceClient client;

    private World lastWorld;
    private boolean wasInWorld;
    private long sessionStart = System.currentTimeMillis();
    private int tick;

    private volatile HypixelLocation location;
    /** server|mode of the last location, to restart the timer when the game changes. */
    private String lastLocationKey;
    /** Tick at which to send /locraw, or -1 for none pending. */
    private volatile int locrawAtTick = -1;
    private int locrawTries;
    /** /locraw replies arriving before this time were asked for by us and are hidden. */
    private volatile long hideLocrawUntil;
    private boolean apiAvailable;
    private volatile long apiLocationMs;
    private long worldChangeMs;

    private long lastActivity = System.currentTimeMillis();
    private float lastYaw;
    private float lastPitch;
    private int lastMouseX;
    private int lastMouseY;
    private Object lastScreen;

    @Override
    public void init() {
        instance = this;
        File cfgDir = FabricLoader.getInstance().getConfigDir().toFile();
        config = new PresenceConfig(new File(cfgDir, "discordpresence.properties"));
        config.load();
        client = PresenceClient.start();
        applyConfig();

        String version = FabricLoader.getInstance().getModContainer("discordpresence")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("1");
        HypixelNames.load(new File(cfgDir, "discordpresence-hypixel-games.json"), version);

        if (FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) {
            try {
                HypixelModApiSupport.init(this);
                apiAvailable = true;
            } catch (Throwable t) {
                System.err.println("[DiscordPresence] Hypixel Mod API not usable, using /locraw: " + t);
            }
        }
        if (FabricLoader.getInstance().isModLoaded("oneconfig")) {
            try {
                OneConfigSupport.init(this);
            } catch (Throwable t) {
                System.err.println("[DiscordPresence] OneConfig page could not be created: " + t);
            }
        }

        MinecraftClientEvents.TICK_END.register(this::onTick);
        ClientCommands.register(this::command, "rpc", "discordpresence");
    }

    public static DiscordPresenceMod get() {
        return instance;
    }

    public PresenceConfig config() {
        return config;
    }

    public void applyConfig() {
        client.configure(config.appId, config.enabled);
    }

    public void reconnect() {
        applyConfig();
        client.reconnect();
    }

    private void onTick(Minecraft mc) {
        tick++;
        World world = mc.theWorld;
        if (world != lastWorld) {
            lastWorld = world;
            onWorldChange(mc, world);
        }
        if (locrawAtTick >= 0 && tick >= locrawAtTick) {
            locrawAtTick = -1;
            sendLocraw(mc);
        }
        trackActivity(mc);
        if (tick % 20 == 0) {
            client.update(build(mc));
        }
    }

    private void onWorldChange(Minecraft mc, World world) {
        boolean inWorld = world != null;
        if (inWorld != wasInWorld) {
            sessionStart = System.currentTimeMillis();
            wasInWorld = inWorld;
            location = null;
            lastLocationKey = null;
        }
        locrawTries = 0;
        worldChangeMs = System.currentTimeMillis();
        // Hypixel moves you between servers by switching worlds: ask where we are now. With the
        // Mod API the location normally arrives by itself, so /locraw is only a late fallback.
        locrawAtTick = inWorld && onHypixel(mc) ? tick + (apiAvailable ? 100 : 30) : -1;
    }

    void onApiLocation(HypixelLocation loc) {
        location = loc;
        apiLocationMs = System.currentTimeMillis();
        locrawAtTick = -1;
    }

    private void sendLocraw(Minecraft mc) {
        if (mc.thePlayer == null || !onHypixel(mc)) {
            return;
        }
        if (apiAvailable && apiLocationMs >= worldChangeMs - 3000) {
            return;
        }
        locrawTries++;
        hideLocrawUntil = System.currentTimeMillis() + 5000;
        mc.thePlayer.sendChatMessage("/locraw");
        if (locrawTries < 2) {
            locrawAtTick = tick + 120;
        }
    }

    /** @return true to hide the message (a /locraw reply we asked for) */
    public boolean onChat(String text) {
        HypixelLocation loc = HypixelLocation.parse(text);
        if (loc == null) {
            return false;
        }
        location = loc;
        locrawAtTick = -1;
        boolean ours = System.currentTimeMillis() < hideLocrawUntil;
        if (ours) {
            hideLocrawUntil = 0;
        }
        return ours;
    }

    private Presence build(Minecraft mc) {
        String name = mc.getSession() != null ? mc.getSession().getUsername() : "";
        String uuid = mc.getSession() != null ? mc.getSession().getPlayerID() : null;
        String head = uuid != null && !uuid.isEmpty() && !config.headUrl.isEmpty()
                ? config.headUrl.replace("{uuid}", uuid.replace("-", "")).replace("{name}", name)
                : null;

        String details;
        String state = null;
        long start = sessionStart;
        int partySize = 0;
        int partyMax = 0;

        if (mc.theWorld == null) {
            details = "In the menus";
        } else if (mc.isSingleplayer()) {
            details = "Singleplayer";
            if (config.showWorldName && mc.getIntegratedServer() != null) {
                state = mc.getIntegratedServer().getWorldName();
            }
        } else if (onHypixel(mc)) {
            HypixelLocation loc = location;
            if (loc == null) {
                details = "Playing on Hypixel";
            } else if (loc.isLimbo()) {
                details = "Hypixel";
                state = "AFK in Limbo";
            } else {
                String mode = loc.modeName();
                details = mode != null ? loc.gameName() + " · " + mode : loc.gameName();

                List<String> lines = sidebar(mc);
                boolean waiting = contains(lines, "Waiting") || contains(lines, "Starting in");
                boolean bedwarsGame = !loc.isLobby() && !waiting && "BEDWARS".equals(loc.gameType());
                BedwarsTeam team = bedwarsGame ? bedwarsTeam(lines) : null;
                int[] standing = bedwarsGame && team == null ? bedwarsStanding(lines) : null;
                if (standing != null && standing[0] > 0) {
                    // No "YOU" line but team lines are there: we're out. Still teams left = spectating.
                    team = standing[1] > 1 ? new BedwarsTeam("", false, 0, true) : BedwarsTeam.GAME_OVER;
                }
                if (bedwarsGame && team == null && !lines.isEmpty() && !String.valueOf(loc.server()).equals(dumpedSidebarFor)) {
                    dumpedSidebarFor = String.valueOf(loc.server());
                    StringBuilder sb = new StringBuilder("Bed Wars team line not found on " + loc.server() + ". Sidebar:");
                    for (String l : lines) {
                        sb.append("\n  ").append(escape(l));
                    }
                    LOG.info(sb.toString());
                }

                if (loc.isLobby()) {
                    state = "MAIN".equalsIgnoreCase(loc.gameType()) ? "Hanging out" : "In the lobby";
                } else if (waiting) {
                    state = "Waiting to start";
                } else if (team == BedwarsTeam.GAME_OVER) {
                    state = "Game over";
                } else if (isSpectating(mc) || (team != null && team.eliminated())) {
                    state = "Spectating";
                } else if (team != null && config.showBedwarsTeam) {
                    state = team.describe();
                } else {
                    state = "In game";
                }
                if (config.showHypixelMap && loc.map() != null && !loc.isLobby()) {
                    state = state + " · " + loc.map();
                }
                if (config.showPlayerCount && waiting) {
                    int[] pc = playerCount(lines);
                    if (pc != null) {
                        partySize = pc[0];
                        partyMax = pc[1];
                    }
                }

                String key = loc.server() + "|" + loc.mode();
                if (!key.equals(lastLocationKey)) {
                    lastLocationKey = key;
                    sessionStart = System.currentTimeMillis();
                    start = sessionStart;
                }
            }
        } else {
            details = "Multiplayer";
            ServerData sd = mc.getCurrentServerData();
            if (config.showServerIp && sd != null && sd.serverIP != null) {
                state = "on " + stripPort(sd.serverIP);
            } else if (sd == null) {
                state = "LAN game";
            }
        }

        if (isIdle()) {
            state = state == null ? "Idle" : "Idle · " + state;
        }
        if (!config.showElapsed) {
            start = 0;
        }

        String largeText = config.largeText.replace("{name}", name);
        String large = config.largeImage;
        String small = null;
        String smallText = null;
        String icon = config.smallIconImage();
        String iconText = config.smallIconText();
        if ("head".equalsIgnoreCase(large)) {
            large = config.showHead ? head : null;
            if (large != null) {
                small = icon;
                smallText = iconText;
            } else if (icon != null) {
                // No head to show: the icon takes the big spot.
                large = icon;
                largeText = iconText;
            }
        } else if (config.showHead) {
            small = head;
            smallText = name;
        } else if (icon != null) {
            small = icon;
            smallText = iconText;
        }

        return new Presence(details, state, start, large, large == null ? null : largeText, small, smallText, partySize, partyMax);
    }

    static boolean onHypixel(Minecraft mc) {
        ServerData sd = mc.getCurrentServerData();
        if (sd == null || sd.serverIP == null || mc.isSingleplayer()) {
            return false;
        }
        String ip = stripPort(sd.serverIP).toLowerCase(Locale.ROOT);
        return ip.equals("hypixel.net") || ip.endsWith(".hypixel.net") || ip.equals("hypixel.io") || ip.endsWith(".hypixel.io");
    }

    /** "host:port" -> "host"; leaves IPv6 addresses (several colons) alone. */
    private static String stripPort(String ip) {
        int c = ip.lastIndexOf(':');
        return c > 0 && ip.indexOf(':') == c ? ip.substring(0, c) : ip;
    }

    private static boolean contains(List<String> lines, String text) {
        for (String line : lines) {
            if (clean(line).contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** @param bed whether the bed is still standing; {@code playersLeft} only counts once it's gone */
    record BedwarsTeam(String team, boolean bed, int playersLeft, boolean eliminated) {
        static final BedwarsTeam GAME_OVER = new BedwarsTeam("", false, 0, true);

        String describe() {
            if (bed) {
                return team + " team · Bed alive";
            }
            return team + " team · Bed broken" + (playersLeft > 1 ? " · " + playersLeft + " left" : "");
        }
    }

    /** Finds our own team line on the Bed Wars sidebar (the one ending in "YOU"). */
    static BedwarsTeam bedwarsTeam(List<String> lines) {
        for (String raw : lines) {
            String line = clean(raw);
            int you = line.lastIndexOf("YOU");
            if (you < 0) {
                continue;
            }
            Matcher m = BW_TEAM.matcher(line.substring(0, you).trim());
            if (!m.find()) {
                continue;
            }
            String[] words = m.group(1).replace(":", "").trim().split(" ");
            String team = words[words.length - 1];
            if (team.isEmpty() || !Character.isLetter(team.charAt(0))) {
                continue;
            }
            String s = m.group(2);
            char c = s.charAt(0);
            if (c == '✓' || c == '✔') {
                return new BedwarsTeam(team, true, 0, false);
            }
            if (Character.isDigit(c)) {
                return new BedwarsTeam(team, false, Integer.parseInt(s), false);
            }
            return new BedwarsTeam(team, false, 0, true);
        }
        return null;
    }

    /** @return {team lines, teams still standing} */
    static int[] bedwarsStanding(List<String> lines) {
        int teams = 0;
        int standing = 0;
        for (String raw : lines) {
            Matcher m = BW_TEAM.matcher(clean(raw));
            if (!m.find() || !m.group(1).trim().matches("[A-Z] [A-Z][a-z]+:")) {
                continue;
            }
            teams++;
            char c = m.group(2).charAt(0);
            if (c == '✓' || c == '✔' || Character.isDigit(c)) {
                standing++;
            }
        }
        return new int[]{teams, standing};
    }

    static String clean(String raw) {
        return BW_JUNK.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /** Makes invisible characters visible for the log. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= 32 && ch < 127) {
                sb.append(ch);
            } else {
                sb.append(String.format("\\u%04x", (int) ch));
            }
        }
        return sb.toString();
    }

    private static boolean isSpectating(Minecraft mc) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) {
            return false;
        }
        if (mc.playerController != null && mc.playerController.getCurrentGameType() == WorldSettings.GameType.SPECTATOR) {
            return true;
        }
        // Hypixel spectators are invisible, can fly, and aren't in creative.
        return p.isInvisible() && p.capabilities.allowFlying && !p.capabilities.isCreativeMode;
    }

    private void trackActivity(Minecraft mc) {
        boolean active = mc.currentScreen != lastScreen;
        lastScreen = mc.currentScreen;
        int mx = Mouse.getX();
        int my = Mouse.getY();
        if (mx != lastMouseX || my != lastMouseY) {
            active = true;
        }
        lastMouseX = mx;
        lastMouseY = my;
        EntityPlayerSP p = mc.thePlayer;
        if (p != null) {
            if (p.rotationYaw != lastYaw || p.rotationPitch != lastPitch) {
                active = true;
            }
            lastYaw = p.rotationYaw;
            lastPitch = p.rotationPitch;
        }
        if (!active && (Mouse.isButtonDown(0) || Mouse.isButtonDown(1) || Mouse.isButtonDown(2))) {
            active = true;
        }
        for (int k = 1; !active && k < 256; k++) {
            if (Keyboard.isKeyDown(k)) {
                active = true;
            }
        }
        if (active) {
            lastActivity = System.currentTimeMillis();
        }
    }

    private boolean isIdle() {
        return config.idleMinutes > 0 && System.currentTimeMillis() - lastActivity > (long) (config.idleMinutes * 60000f);
    }

    /** "Players: 6/8" on the pre-game sidebar. */
    private static int[] playerCount(List<String> lines) {
        for (String line : lines) {
            Matcher m = PLAYERS.matcher(clean(line));
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        }
        return null;
    }

    /** The sidebar's lines as shown, without formatting codes. */
    private static List<String> sidebar(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc.theWorld == null) {
            return out;
        }
        Scoreboard sb = mc.theWorld.getScoreboard();
        ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
        if (obj == null) {
            return out;
        }
        Collection<Score> scores = sb.getSortedScores(obj);
        for (Score s : scores) {
            String n = s.getPlayerName();
            if (n == null || n.startsWith("#")) {
                continue;
            }
            String line = ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(n), n);
            out.add(EnumChatFormatting.getTextWithoutFormattingCodes(line));
        }
        return out;
    }

    private void command(String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "appid" -> {
                if (args.length < 2 || !args[1].chars().allMatch(Character::isDigit)) {
                    ClientCommands.chat(P + "Usage: /rpc appid <application id>  (the number from discord.com/developers/applications)");
                    return;
                }
                config.appId = args[1];
                saveAndApply();
                ClientCommands.chat(P + "Application id set. Connecting...");
            }
            case "on", "off", "toggle" -> {
                config.enabled = sub.equals("toggle") ? !config.enabled : sub.equals("on");
                saveAndApply();
                ClientCommands.chat(P + "Rich presence " + (config.enabled ? "enabled" : "disabled"));
            }
            case "ip", "world", "map", "players", "timer", "head" -> {
                boolean v = args.length > 1
                        ? args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true")
                        : !flag(sub);
                setFlag(sub, v);
                saveAndApply();
                ClientCommands.chat(P + sub + ": " + (v ? "shown" : "hidden"));
            }
            case "image" -> {
                if (args.length < 2) {
                    ClientCommands.chat(P + "Usage: /rpc image <asset key | https://url | head>");
                    return;
                }
                config.largeImage = args[1];
                saveAndApply();
                ClientCommands.chat(P + "Large image: " + args[1]);
            }
            case "icon" -> {
                if (args.length < 2) {
                    ClientCommands.chat(P + "Small icon: " + config.smallIcon + ". Usage: /rpc icon <oneclient|oneconfig|none|asset key|https://url>");
                    return;
                }
                config.smallIcon = args[1];
                saveAndApply();
                ClientCommands.chat(P + "Small icon: " + args[1]);
            }
            case "headsource" -> {
                String names = String.join("|", PresenceConfig.HEAD_SOURCE_NAMES);
                int idx = args.length > 1 ? Arrays.asList(PresenceConfig.HEAD_SOURCE_NAMES).indexOf(args[1].toLowerCase(Locale.ROOT)) : -1;
                if (idx < 0) {
                    int cur = PresenceConfig.headSourceIndex(config.headUrl);
                    ClientCommands.chat(P + "Head source: " + (cur < PresenceConfig.HEAD_SOURCE_NAMES.length ? PresenceConfig.HEAD_SOURCE_NAMES[cur] : "custom")
                            + ". Usage: /rpc headsource <" + names + ">");
                    return;
                }
                config.headUrl = PresenceConfig.HEAD_SOURCES[idx];
                saveAndApply();
                ClientCommands.chat(P + "Head source: " + PresenceConfig.HEAD_SOURCE_NAMES[idx]);
            }
            case "reconnect" -> {
                client.reconnect();
                ClientCommands.chat(P + "Reconnecting to Discord...");
            }
            case "debug" -> {
                Minecraft mc = Minecraft.getMinecraft();
                List<String> lines = sidebar(mc);
                HypixelLocation loc = location;
                StringBuilder sb = new StringBuilder("/rpc debug\n  location: " + loc + "\n  spectating: " + isSpectating(mc)
                        + ", idle: " + isIdle() + "\n  bed wars team: " + bedwarsTeam(lines) + "\n  sidebar:");
                for (String l : lines) {
                    sb.append("\n    ").append(escape(l));
                }
                LOG.info(sb.toString());
                ClientCommands.chat(P + "Team: " + bedwarsTeam(lines) + ", spectating: " + isSpectating(mc) + ", idle: " + isIdle());
                for (String l : lines) {
                    ClientCommands.chat(EnumChatFormatting.GRAY + escape(l));
                }
                ClientCommands.chat(EnumChatFormatting.GRAY + "(also written to logs/latest.log)");
            }
            case "where" -> {
                HypixelLocation loc = location;
                ClientCommands.chat(P + (loc == null ? "No Hypixel location yet." : loc.toString()));
            }
            default -> {
                ClientCommands.chat(P + "Status: " + client.status());
                ClientCommands.chat(EnumChatFormatting.GRAY + "/rpc on|off, appid <id>, image <key|url|head>, headsource <name>, icon <name>, reconnect");
                ClientCommands.chat(EnumChatFormatting.GRAY + "/rpc ip|world|map|players|timer|head [on|off]");
                ClientCommands.chat(EnumChatFormatting.GRAY + "ip " + onOff(config.showServerIp) + ", world " + onOff(config.showWorldName)
                        + ", map " + onOff(config.showHypixelMap) + ", players " + onOff(config.showPlayerCount)
                        + ", timer " + onOff(config.showElapsed) + ", head " + onOff(config.showHead));
            }
        }
    }

    private boolean flag(String k) {
        return switch (k) {
            case "ip" -> config.showServerIp;
            case "world" -> config.showWorldName;
            case "map" -> config.showHypixelMap;
            case "players" -> config.showPlayerCount;
            case "timer" -> config.showElapsed;
            default -> config.showHead;
        };
    }

    private void setFlag(String k, boolean v) {
        switch (k) {
            case "ip" -> config.showServerIp = v;
            case "world" -> config.showWorldName = v;
            case "map" -> config.showHypixelMap = v;
            case "players" -> config.showPlayerCount = v;
            case "timer" -> config.showElapsed = v;
            default -> config.showHead = v;
        }
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private void saveAndApply() {
        config.save();
        applyConfig();
    }
}
