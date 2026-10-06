package dev.mineagent.runtime.neoforge.client.nativeui;
import com.lowdragmc.lowdraglib2.client.RenderTargetScope;
import com.lowdragmc.lowdraglib2.core.mixins.accessor.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGuiRendererExt;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.neoforged.neoforge.client.gui.*;
import java.util.*;
/** 26.1.2 private surface backend. The newer LDLib2 contract lives in the target overlay. */
final class NativeCaptureBackend {
    static GuiRenderer create(GuiRenderState state){
        var main=((GameRendererAccessor)(Object)Minecraft.getInstance().gameRenderer).ldlib2$getGuiRenderer();var shared=(IGuiRendererExt)(Object)main;
        var renderer=new GuiRenderer(state,shared.ldlib2$getBufferSource(),shared.ldlib2$getSubmitNodeCollector(),shared.ldlib2$getFeatureRenderDispatcher(),List.of());
        var pools=new HashMap<Class<? extends PictureInPictureRenderState>,PictureInPictureRendererPool<?>>();
        shared.ldlib2$getPictureInPictureRendererPools().forEach((kind,pool)->pools.put(kind,pool(((PictureInPictureRendererPoolAccessor)pool).ldlib2$getFactory(),shared)));
        ((IGuiRendererExt)(Object)renderer).ldlib2$setPictureInPictureRendererPools(pools);return renderer;
    }
    static void render(GuiRenderer renderer,RenderTarget target,int guiWidth,int guiHeight,int width,int height,int scale){
        var fog=IGuiRendererExt.ldlib2$getLastFogBuffer();if(fog==null)throw new IllegalStateException("CAPTURE_PAINT_PENDING");
        try(var output=RenderTargetScope.redirect(target.getColorTextureView(),target.getDepthTextureView())){
            IGuiRendererExt.ldlib2$pushTargetOverride(target);IGuiRendererExt.ldlib2$pushOrthoOverride(guiWidth,guiHeight,width,height,scale);
            try{renderer.render(fog);}finally{IGuiRendererExt.ldlib2$popOrthoOverride();IGuiRendererExt.ldlib2$popTargetOverride();renderer.endFrame();}
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static PictureInPictureRendererPool<?> pool(PictureInPictureRendererRegistration<?> factory,IGuiRendererExt renderer){return new PictureInPictureRendererPool(factory,renderer.ldlib2$getBufferSource());}
    private NativeCaptureBackend(){}
}
