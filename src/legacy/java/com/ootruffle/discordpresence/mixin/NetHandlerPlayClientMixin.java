package com.ootruffle.discordpresence.mixin;

import com.ootruffle.discordpresence.DiscordPresenceMod;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S02PacketChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sees chat before it is shown, so our own /locraw replies can be read and hidden. */
@Mixin(NetHandlerPlayClient.class)
public class NetHandlerPlayClientMixin {
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void discordpresence$locraw(S02PacketChat packet, CallbackInfo ci) {
        // Type 2 is the action bar.
        if (packet.getType() == 2 || packet.getChatComponent() == null) {
            return;
        }
        DiscordPresenceMod mod = DiscordPresenceMod.get();
        if (mod != null && mod.onChat(packet.getChatComponent().getUnformattedText())) {
            ci.cancel();
        }
    }
}
