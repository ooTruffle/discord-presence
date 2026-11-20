package com.ootruffle.discordpresence.ornithe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny client-side command system: {@code ClientCommandsMixin} hands every outgoing chat
 * message to {@link #dispatch}, and registered commands never reach the server.
 */
public final class ClientCommands {
    private static final Map<String, Handler> COMMANDS = new HashMap<>();
    private static GuiScreen pendingScreen;
    private static boolean tickRegistered;

    private ClientCommands() {
    }

    public interface Handler {
        void run(String[] args) throws Exception;
    }

    public static void register(Handler handler, String... names) {
        for (String name : names) {
            COMMANDS.put(name.toLowerCase(Locale.ROOT), handler);
        }
        if (!tickRegistered) {
            tickRegistered = true;
            // Screens opened from a command are shown on the next tick, after the chat closes.
            MinecraftClientEvents.TICK_START.register(mc -> {
                if (pendingScreen != null) {
                    GuiScreen screen = pendingScreen;
                    pendingScreen = null;
                    mc.displayGuiScreen(screen);
                }
            });
        }
    }

    /** @return true when the message was one of our commands and must not be sent */
    public static boolean dispatch(String message) {
        if (message == null || !message.startsWith("/")) {
            return false;
        }
        String[] parts = message.substring(1).trim().split(" +");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return false;
        }
        Handler handler = COMMANDS.get(parts[0].toLowerCase(Locale.ROOT));
        if (handler == null) {
            return false;
        }
        try {
            handler.run(Arrays.copyOfRange(parts, 1, parts.length));
        } catch (Exception e) {
            chat(EnumChatFormatting.RED + "Error: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    public static void openScreen(GuiScreen screen) {
        pendingScreen = screen;
    }

    public static void chat(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(text));
        }
    }
}
