package dev.mineagent.runtime.neoforge.mixin.client;
import com.cinemamod.mcef.MCEFBrowser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
@Mixin(value=MCEFBrowser.class,remap=false)
public interface McefPaintAccess {
    @Invoker("onPaintRenderThread_MCEF") void mineagent$paint(boolean popup,Rectangle[] dirty,ByteBuffer pixels,int width,int height,Rectangle popupRect,boolean shown);
}
