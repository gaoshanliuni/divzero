package dev.mineagent.runtime.neoforge.client.webui;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mineagent.runtime.client.webui.FrameResourceRetirement;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Pinned MCEF paints during GameRenderer.render, after the GUI has extracted its texture view. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class McefTextureRetirement {
    private static final FrameResourceRetirement RETIRED=new FrameResourceRetirement();
    private static boolean stopping;
    private static long textures,views;
    private McefTextureRetirement(){}
    private static boolean managed(GpuTexture texture){
        var browser=WebGuiHostAdapter.INSTANCE.browser();
        return texture!=null&&(RETIRED.contains(texture)||WebGuiPopupCompositor.ownsTexture(texture)||browser!=null&&browser.getRenderer().getTexture()==texture);
    }
    public static void texture(GpuTexture texture){
        RenderSystem.assertOnRenderThread();
        if(!stopping&&managed(texture)){textures++;RETIRED.retire(texture,texture::close);}else texture.close();
    }
    public static void view(GpuTextureView view,GpuTexture texture){
        RenderSystem.assertOnRenderThread();
        if(!stopping&&managed(texture)){views++;RETIRED.retire(view,view::close);}else view.close();
    }
    @SubscribeEvent public static void afterFrame(RenderFrameEvent.Post event){RETIRED.afterFrame();}
    public static long textures(){return textures;}
    public static long views(){return views;}
    public static int pending(){return RETIRED.size();}
    public static void shutdown(){RenderSystem.assertOnRenderThread();stopping=true;RETIRED.drain();}
}
