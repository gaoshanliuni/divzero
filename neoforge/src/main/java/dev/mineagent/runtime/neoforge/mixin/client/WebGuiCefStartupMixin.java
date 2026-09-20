package dev.mineagent.runtime.neoforge.mixin.client;

import dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the actual startup flags, not settings which may have changed since CEF startup. */
@Mixin(targets = "org.cef.CefApp", remap = false)
public abstract class WebGuiCefStartupMixin {
    @Inject(method = "startup", at = @At("HEAD"))
    private static void mineagent$recordSecurity(String[] args, CallbackInfoReturnable<Boolean> result) {
        WebGuiHostAdapter.recordStartupFlags(args);
    }
}
