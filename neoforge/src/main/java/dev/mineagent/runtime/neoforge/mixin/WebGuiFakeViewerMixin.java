package dev.mineagent.runtime.neoforge.mixin;

import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An AI body has no negotiated browser channel. Never send join HUD/origin payloads to its fake connection. */
@Mixin(targets = "land.webgui.WebviewJoinHud", remap = false)
public abstract class WebGuiFakeViewerMixin {
    @Inject(method = "onPlayerJoin", at = @At("HEAD"), cancellable = true)
    private static void mineagent$skipFakeViewer(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo callback) {
        if (event.getEntity() instanceof MineAgentPlayer) callback.cancel();
    }
}
