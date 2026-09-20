package dev.mineagent.runtime.neoforge.mixin.client;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.mineagent.runtime.client.webui.WebGuiTheme;
import dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Tint only MineAgent's owned WebGUI HUD; do not alter another mod's browser or the capture source texture. */
@Mixin(targets="land.webgui.WebHudOverlay",remap=false)
public abstract class WebGuiTranslucencyMixin {
    @Redirect(method="onHudRender",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"),require=1)
    private static void mineagent$glass(GuiGraphicsExtractor graphics,RenderPipeline pipeline,Identifier texture,int x,int y,float u,float v,int width,int height,int textureWidth,int textureHeight){
        if(WebGuiHostAdapter.INSTANCE.owns(land.webgui.WebSession.hudBrowser())&&dev.mineagent.runtime.neoforge.client.webui.WebGuiAtlasCompositor.render(graphics,pipeline,texture,width,height))return;
        graphics.blit(pipeline,texture,x,y,u,v,width,height,textureWidth,textureHeight,WebGuiTheme.tint(WebGuiHostAdapter.INSTANCE.owns(land.webgui.WebSession.hudBrowser())));
    }
}
