package dev.mineagent.runtime.neoforge.mixin.client;

import dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pinned 1.6.2 router otherwise accepts close/log/custom events from every frame. */
@Mixin(targets = "land.webgui.WebviewPageToClientBridge$1", remap = false)
public abstract class WebGuiDefaultBridgeMixin {
    @Inject(method = "onQuery", at = @At("HEAD"), cancellable = true)
    private void mineagent$blockUnscopedBridge(CefBrowser browser, CefFrame frame, long queryId, String request,
                                               boolean persistent, CefQueryCallback callback, CallbackInfoReturnable<Boolean> result) {
        if (WebGuiHostAdapter.INSTANCE.owns(browser)) {
            callback.failure(403, "USE_SCOPED_MINEAGENT_BRIDGE");
            result.setReturnValue(true);
        }
    }
}
