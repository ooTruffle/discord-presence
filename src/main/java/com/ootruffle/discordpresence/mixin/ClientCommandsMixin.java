package com.ootruffle.discordpresence.mixin;

import com.ootruffle.discordpresence.ornithe.ClientCommands;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs our client commands before the message is sent to the server. */
@Mixin(EntityPlayerSP.class)
public class ClientCommandsMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void discordpresence$clientCommands(String message, CallbackInfo ci) {
        if (ClientCommands.dispatch(message)) {
            ci.cancel();
        }
    }
}
