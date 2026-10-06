package dev.mineagent.runtime.neoforge.client.nativeui;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGuiRendererExt;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
/** LDLib2 26.2+ creates independent pools and scopes the render target and WindowRenderState. */
final class NativeCaptureBackend {
    static GuiRenderer create(GuiRenderState state){return IGuiRendererExt.ldlib2$createSubRenderer(state);}
    static void render(GuiRenderer renderer,RenderTarget target,int guiWidth,int guiHeight,int width,int height,int scale){
        try(var output=IGuiRendererExt.ldlib2$targetOverride(target);var window=IGuiRendererExt.ldlib2$windowOverride(target,scale)){
            try{renderer.render();}finally{renderer.endFrame();}
        }
    }
    private NativeCaptureBackend(){}
}
