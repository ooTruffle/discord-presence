package com.ootruffle.discordpresence;

import net.hypixel.data.type.ServerType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

/**
 * Location updates pushed by the Hypixel Mod API, used instead of /locraw when that mod is
 * installed. Kept in its own class so nothing loads the API's classes when it is missing.
 */
final class HypixelModApiSupport {
    private HypixelModApiSupport() {
    }

    static void init(DiscordPresenceMod mod) {
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.createHandler(ClientboundLocationPacket.class, packet -> {
            String type = packet.getServerType().map(ServerType::name).orElse(null);
            mod.onApiLocation(new HypixelLocation(packet.getServerName(), type,
                    packet.getMode().orElse(null), packet.getMap().orElse(null), packet.getLobbyName().orElse(null)));
        });
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
    }
}
