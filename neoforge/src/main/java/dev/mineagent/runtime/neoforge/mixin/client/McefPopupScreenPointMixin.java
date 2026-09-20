package dev.mineagent.runtime.neoforge.mixin.client;
import org.cef.browser.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.awt.Point;
@Mixin(value=CefBrowserOsr.class,remap=false)
public abstract class McefPopupScreenPointMixin {
    @Inject(method="getScreenPoint",at=@At("HEAD"),cancellable=true)
    private void mineagent$screenPoint(CefBrowser browser,Point input,CallbackInfoReturnable<Point> cir){var p=dev.mineagent.runtime.neoforge.client.webui.WebGuiPopupCompositor.screenPoint(browser,input);if(p!=null)cir.setReturnValue(p);}
}
