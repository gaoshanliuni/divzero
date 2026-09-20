package dev.mineagent.runtime.neoforge.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.OoO00o00o0OO0Ooo0O0OOOOo", remap = false)
public abstract class PinnedYsmRendererMixin {
    @Inject(
            method = "OO0OoO00ooOOo0o00O000OoO(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lcom/elfmcys/yesstevemodel/OoOo0OOOoo0o0oOOoooooo0O;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void mineagent$recordYsmRenderer(
            @Coerce Object renderState,
            @Coerce Object ysmWrapper,
            @Coerce Object poseStack,
            @Coerce Object collector,
            @Coerce Object cameraState,
            CallbackInfo callback
    ) {
        dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.recordYsmCallback(ysmWrapper);
    }
}
