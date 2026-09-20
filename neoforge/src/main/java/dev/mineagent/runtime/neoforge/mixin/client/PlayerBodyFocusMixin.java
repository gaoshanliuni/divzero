package dev.mineagent.runtime.neoforge.mixin.client;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Window.class)
public abstract class PlayerBodyFocusMixin {
    @Inject(method="onFocus",at=@At("HEAD"))
    private void mineagent$bodyFocus(long window,boolean focused,CallbackInfo ci){if(!focused)dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.contextBoundary("FOCUS_LOST");}
}
