package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public abstract class PlayerBodyMouseMixin {
    @Inject(method="grabMouse",at=@At("HEAD"),cancellable=true)
    private void divzero$keepAutonomyCursorFree(CallbackInfo ci){if(dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.active())ci.cancel();}
    @Inject(method="onMove",at=@At("HEAD"),cancellable=true)
    private void mineagent$bodyMotion(long window,double x,double y,double dx,double dy,CallbackInfo ci){dev.mineagent.runtime.neoforge.client.body.TakeoverCameraClient.mouseMoved(window,x,y);if(dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.blockMotion(window))ci.cancel();}
}
