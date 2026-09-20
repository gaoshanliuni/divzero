package dev.mineagent.runtime.neoforge.mixin.client;
import com.cinemamod.mcef.MCEFBrowser;
import dev.mineagent.runtime.neoforge.client.webui.McefPaintBoundary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
@Mixin(value=MCEFBrowser.class,remap=false)
public abstract class McefPaintBoundaryMixin {
    @Inject(method="onPaintRenderThread_MCEF",at=@At("HEAD"),cancellable=true)
    private void mineagent$defer(boolean popup,Rectangle[] dirty,ByteBuffer pixels,int width,int height,Rectangle popupRect,boolean shown,CallbackInfo ci){if(McefPaintBoundary.defer((MCEFBrowser)(Object)this,popup,dirty,pixels,width,height,popupRect,shown))ci.cancel();else dev.mineagent.runtime.neoforge.client.webui.WebGuiPaintComposition.beforePaint((MCEFBrowser)(Object)this,popup,pixels,width,height);}
    @Inject(method="onPaintRenderThread_MCEF",at=@At("RETURN"))
    private void mineagent$paintEnd(boolean popup,Rectangle[] dirty,ByteBuffer pixels,int width,int height,Rectangle popupRect,boolean shown,CallbackInfo ci){dev.mineagent.runtime.neoforge.client.webui.WebGuiPaintComposition.afterPaint();}
}
