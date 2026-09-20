package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public abstract class PlayerBodyMouseMixin {
    @Inject(method="onMove",at=@At("HEAD"),cancellable=true)
    private void mineagent$bodyMotion(long window,double x,double y,CallbackInfo ci){if(dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.blockMotion(window))ci.cancel();}
}
