package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Private-view capture has its own render state and transparent target; it never crops the game framebuffer. */
public final class NativeViewCapture {
    public record Captured(String documentId,String layoutIdentity,int width,int height,double frameX,double frameY,double scaleX,double scaleY,byte[] png){public Captured{png=png.clone();}@Override public byte[] png(){return png.clone();}}
    private static boolean busy;
    public static CompletableFuture<Captured> capture(LdInterfaceRenderer.Rendered isolated,String document,String layout,int guiWidth,int guiHeight,BooleanSupplier current,LdInterfaceRenderer.Rendered source){
        var mc=Minecraft.getInstance();if(!mc.isSameThread())return CompletableFuture.failedFuture(new IllegalStateException("NATIVE_CAPTURE_CLIENT_THREAD"));
        if(busy||!current.getAsBoolean()){isolated.close();return CompletableFuture.failedFuture(new IllegalStateException("NATIVE_CAPTURE_UNAVAILABLE"));}
        double scale=mc.getWindow().getGuiScale();int width=(int)Math.ceil(guiWidth*scale),height=(int)Math.ceil(guiHeight*scale);
        if(width<1||height<1||(long)width*height>2_097_152){isolated.close();return CompletableFuture.failedFuture(new IllegalArgumentException("CAPTURE_PIXEL_BUDGET"));}
        var result=new CompletableFuture<Captured>();busy=true;OffscreenSurface surface=null;GuiRenderer renderer=null;
        try {
            surface=new OffscreenSurface(mc.getWindow().handle(),width,height,width,height);
            var ui=isolated.ui;isolated.root.getLayout().width(guiWidth).height(guiHeight).left(0).top(0);ui.init(guiWidth,guiHeight);source.copyScrollTo(isolated);
            var state=new GuiRenderState();var graphics=new GuiGraphicsExtractor(mc,state,-10000,-10000);
            renderer=NativeCaptureBackend.create(state);
            var target=surface.target();
            try(var selected=UISurface.push(surface);var active=ModularUI.scopedActive(ui)){
                ModularUIClientAccess.getWidget(ui).extractRenderState(graphics,-10000,-10000,0);
                if(!source.sameGeometry(isolated))throw new IllegalStateException("NATIVE_CAPTURE_LAYOUT_CHANGED");
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(),0,target.getDepthTexture(),1.0);
                NativeCaptureBackend.render(renderer,target,guiWidth,guiHeight,width,height,(int)scale);
            }
            var capturedSurface=surface;var capturedRenderer=renderer;
            net.minecraft.client.Screenshot.takeScreenshot(target,image->mc.execute(()->{
                try(image){if(!current.getAsBoolean())throw new IllegalStateException("STALE_VIEW");var full=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);full.setRGB(0,0,image.getWidth(),image.getHeight(),image.getPixels(),0,image.getWidth());var size=dev.mineagent.runtime.client.webui.UiCaptureSizing.fit(full.getWidth(),full.getHeight(),dev.mineagent.runtime.api.model.ModelImage.MAX_PIXELS);BufferedImage output=full;
                    if(size.width()!=full.getWidth()||size.height()!=full.getHeight()){output=new BufferedImage(size.width(),size.height(),BufferedImage.TYPE_INT_ARGB);var painter=output.createGraphics();try{painter.drawImage(full,0,0,size.width(),size.height(),null);}finally{painter.dispose();full.flush();}}
                    try(var bytes=new ByteArrayOutputStream()){if(!javax.imageio.ImageIO.write(output,"PNG",bytes)||bytes.size()>2_097_152)throw new IllegalArgumentException("CAPTURE_PNG_BUDGET");result.complete(new Captured(document,layout,size.width(),size.height(),0,0,(double)size.width()/guiWidth,(double)size.height()/guiHeight,bytes.toByteArray()));}finally{output.flush();}
                }catch(Exception failure){result.completeExceptionally(failure);}finally{isolated.close();capturedRenderer.close();capturedSurface.destroy();busy=false;}
            }));
        }catch(Exception failure){if(renderer!=null)renderer.close();if(surface!=null)surface.destroy();isolated.close();busy=false;result.completeExceptionally(failure);}
        return result.orTimeout(5,TimeUnit.SECONDS);
    }
    private NativeViewCapture(){}
}
