package dev.mineagent.runtime.neoforge.mixin.client;
import com.cinemamod.mcef.MCEFBrowser;
import org.cef.browser.CefBrowser;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.awt.Rectangle;import java.nio.ByteBuffer;
@Mixin(value=MCEFBrowser.class,remap=false)
public abstract class McefPopupMixin {
    @Shadow protected volatile Rectangle popupSize;
    @Shadow protected volatile ByteBuffer popupGraphics;
    @Shadow protected volatile boolean popupDrawn;
    @Inject(method="onPopupShow",at=@At("RETURN"))
    private void mineagent$popup(CefBrowser b,boolean show,CallbackInfo ci){net.minecraft.client.Minecraft.getInstance().execute(()->dev.mineagent.runtime.neoforge.client.webui.WebGuiPopupCompositor.shown((MCEFBrowser)(Object)this,show));}
    @Inject(method="onPopupSize",at=@At("HEAD"),cancellable=true)
    private void mineagent$size(CefBrowser b,Rectangle size,CallbackInfo ci){
        if(!dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.owns(b))return;
        var copy=size==null?null:new Rectangle(size);net.minecraft.client.Minecraft.getInstance().execute(()->dev.mineagent.runtime.neoforge.client.webui.WebGuiPopupCompositor.size((MCEFBrowser)(Object)this,copy));
        if(dev.mineagent.runtime.neoforge.client.webui.WebGuiAtlasCompositor.active()){popupSize=copy;popupGraphics=null;popupDrawn=false;ci.cancel();}
    }
}
