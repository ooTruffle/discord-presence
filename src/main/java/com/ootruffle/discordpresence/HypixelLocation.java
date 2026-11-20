package com.ootruffle.discordpresence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Where the player is on Hypixel, from /locraw or the Hypixel Mod API location packet. */
public record HypixelLocation(String server, String gameType, String mode, String map, String lobbyName) {
    private static final Map<String, String> GAMES = new HashMap<>();
    private static final Map<String, String> MODES = new HashMap<>();

    // Built-in fallbacks; HypixelNames' downloaded names win when available.
    static {
        game("MAIN", "Main Lobby");
        game("BEDWARS", "Bed Wars");
        game("SKYWARS", "SkyWars");
        game("DUELS", "Duels");
        game("MURDER_MYSTERY", "Murder Mystery");
        game("BUILD_BATTLE", "Build Battle");
        game("ARCADE", "Arcade");
        game("TNTGAMES", "TNT Games");
        game("UHC", "UHC Champions");
        game("SPEED_UHC", "Speed UHC");
        game("PIT", "The Pit");
        game("HOUSING", "Housing");
        game("PROTOTYPE", "Prototype");
        game("LEGACY", "Classic Games");
        game("WALLS3", "Mega Walls");
        game("SURVIVAL_GAMES", "Blitz SG");
        game("MCGO", "Cops and Crims");
        game("BATTLEGROUND", "Warlords");
        game("SUPER_SMASH", "Smash Heroes");
        game("WOOL_GAMES", "Wool Games");
        game("SMP", "SMP");
        game("REPLAY", "Replay");
        game("SKYBLOCK", "SkyBlock");
        game("TOURNAMENT", "Tournament Hall");
        game("QUAKECRAFT", "Quakecraft");
        game("WALLS", "Walls");
        game("PAINTBALL", "Paintball");
        game("VAMPIREZ", "VampireZ");
        game("ARENA", "Arena Brawl");
        game("GINGERBREAD", "Turbo Kart Racers");
        mode("BEDWARS_EIGHT_ONE", "Solo");
        mode("BEDWARS_EIGHT_TWO", "Doubles");
        mode("BEDWARS_FOUR_THREE", "3v3v3v3");
        mode("BEDWARS_FOUR_FOUR", "4v4v4v4");
        mode("BEDWARS_TWO_FOUR", "4v4");
        mode("BEDWARS_CASTLE", "Castle");
        mode("BEDWARS_PRACTICE", "Practice");
        mode("solo_normal", "Solo Normal");
        mode("solo_insane", "Solo Insane");
        mode("teams_normal", "Teams Normal");
        mode("teams_insane", "Teams Insane");
        mode("ranked_normal", "Ranked");
        mode("mega_normal", "Mega");
        mode("mega_doubles", "Mega Doubles");
        mode("DUELS_CLASSIC_DUEL", "Classic");
        mode("DUELS_SW_DUEL", "SkyWars");
        mode("DUELS_SW_DOUBLES", "SkyWars Doubles");
        mode("DUELS_UHC_DUEL", "UHC");
        mode("DUELS_UHC_DOUBLES", "UHC Doubles");
        mode("DUELS_UHC_FOUR", "UHC Teams");
        mode("DUELS_UHC_MEETUP", "UHC Deathmatch");
        mode("DUELS_BRIDGE_DUEL", "Bridge");
        mode("DUELS_BRIDGE_DOUBLES", "Bridge Doubles");
        mode("DUELS_BRIDGE_THREES", "Bridge 3v3");
        mode("DUELS_BRIDGE_FOUR", "Bridge 4v4");
        mode("DUELS_BRIDGE_2V2V2V2", "Bridge 2v2v2v2");
        mode("DUELS_BRIDGE_3V3V3V3", "Bridge 3v3v3v3");
        mode("DUELS_CAPTURE_THREES", "Bridge CTF");
        mode("DUELS_SUMO_DUEL", "Sumo");
        mode("DUELS_OP_DUEL", "OP");
        mode("DUELS_OP_DOUBLES", "OP Doubles");
        mode("DUELS_BOW_DUEL", "Bow");
        mode("DUELS_BOWSPLEEF_DUEL", "Bow Spleef");
        mode("DUELS_COMBO_DUEL", "Combo");
        mode("DUELS_POTION_DUEL", "NoDebuff");
        mode("DUELS_BLITZ_DUEL", "Blitz");
        mode("DUELS_MW_DUEL", "Mega Walls");
        mode("DUELS_MW_DOUBLES", "Mega Walls Doubles");
        mode("DUELS_PARKOUR_EIGHT", "Parkour");
        mode("DUELS_BOXING_DUEL", "Boxing");
        mode("DUELS_DUEL_ARENA", "Duel Arena");
        mode("DUELS_SPLEEF_DUEL", "Spleef");
        mode("DUELS_QUAKE_DUEL", "Quake");
        mode("MURDER_CLASSIC", "Classic");
        mode("MURDER_DOUBLE_UP", "Double Up");
        mode("MURDER_ASSASSINS", "Assassins");
        mode("MURDER_INFECTION", "Infection");
        mode("BUILD_BATTLE_SOLO_NORMAL", "Solo");
        mode("BUILD_BATTLE_TEAMS_NORMAL", "Teams");
        mode("BUILD_BATTLE_SOLO_PRO", "Pro");
        mode("BUILD_BATTLE_GUESS_THE_BUILD", "Guess the Build");
        mode("BUILD_BATTLE_SPEED_BUILDERS", "Speed Builders");
        mode("TNTRUN", "TNT Run");
        mode("PVPRUN", "PVP Run");
        mode("BOWSPLEEF", "Bow Spleef");
        mode("TNTAG", "TNT Tag");
        mode("CAPTURE", "Wizards");
        mode("BEDWARS_TWO_ONE_DUELS", "Bed Wars Duels");
    }

    private static void game(String id, String name) {
        GAMES.put(id, name);
    }

    private static void mode(String id, String name) {
        MODES.put(id, name);
    }

    /** Parses a /locraw reply, or returns null when the text isn't one. */
    public static HypixelLocation parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!t.startsWith("{") || !t.endsWith("}") || !t.contains("\"server\"")) {
            return null;
        }
        try {
            JsonObject o = new JsonParser().parse(t).getAsJsonObject();
            return new HypixelLocation(str(o, "server"), str(o, "gametype"), str(o, "mode"), str(o, "map"), str(o, "lobbyname"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public boolean isLimbo() {
        return "limbo".equalsIgnoreCase(server);
    }

    public boolean isLobby() {
        return lobbyName != null || "LOBBY".equalsIgnoreCase(mode) || "MAIN".equalsIgnoreCase(gameType);
    }

    public String gameName() {
        if (gameType == null) {
            return "Hypixel";
        }
        if ("MAIN".equalsIgnoreCase(gameType)) {
            return GAMES.get("MAIN");
        }
        String n = HypixelNames.game(gameType);
        if (n == null) {
            n = GAMES.get(gameType);
        }
        return n != null ? n : pretty(gameType);
    }

    /** The mode's display name, or null in lobbies. */
    public String modeName() {
        if (mode == null || isLobby()) {
            return null;
        }
        String n = HypixelNames.mode(gameType, mode);
        if (n == null) {
            n = MODES.get(mode);
        }
        if (n != null) {
            return n;
        }
        String m = mode;
        if (gameType != null && m.toUpperCase(Locale.ROOT).startsWith(gameType.toUpperCase(Locale.ROOT) + "_")) {
            m = m.substring(gameType.length() + 1);
        }
        return pretty(m);
    }

    /** SOME_ID -> Some Id */
    static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("_+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
