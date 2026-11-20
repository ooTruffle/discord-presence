package com.ootruffle.discordpresence;

import net.hypixel.data.type.ServerType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

/** Uses Hypixel's push location API when present, with /locraw as a fallback. */
final class HypixelModApiSupport {
    private HypixelModApiSupport() { }

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
