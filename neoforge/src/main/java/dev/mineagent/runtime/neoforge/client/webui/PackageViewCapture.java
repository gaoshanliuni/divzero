package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.mineagent.runtime.client.webui.UiCaptureRegion;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual MCEF GPU texture ROI, never a desktop/game framebuffer or package-supplied rectangle. */
public final class PackageViewCapture {
    public record Captured(String documentId,String layoutIdentity,int width,int height,double frameX,double frameY,
                           double scaleX,double scaleY,byte[] png){
        public Captured{png=png.clone();}
        @Override public byte[] png(){return png.clone();}
    }
    private record Pending(CefBrowser browser,String shellUrl,String view,String url,BooleanSupplier current,CompletableFuture<JsonObject> result){}
    private static final Gson JSON=new Gson();
    private static final Map<UUID,Pending> pending=new HashMap<>();
    private static final ThreadPoolExecutor ENCODER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mineagent-view-png");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static CefMessageRouter router;
    private static CompletableFuture<Captured> active;
    private static final int MAX_READ_PIXELS=2_097_152;
    private PackageViewCapture(){}
    static CompletableFuture<Boolean> sameTarget(byte[] before,byte[] after,dev.mineagent.runtime.api.ui.UiCapture.Manifest manifest,dev.mineagent.runtime.client.webui.UiCaptureCoordinates.Bounds bounds){
        return CompletableFuture.supplyAsync(()->{try{return dev.mineagent.runtime.client.webui.UiCaptureCoordinates.sameTarget(before,after,manifest,bounds);}
            catch(IOException invalid){throw new IllegalStateException("CAPTURE_PNG_DECODE",invalid);}},ENCODER);
    }
    public static void register(){
        if(router!=null)return;
        router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentCaptureQuery","mineagentCaptureQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter(){
            @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){
                if(request==null||persistent||request.length()>8192||!WebGuiHostAdapter.INSTANCE.acceptsTrustedFrame(browser,frame,request.length())){callback.failure(403,"CAPTURE_SOURCE");return true;}
                String url=frame.getURL();Minecraft.getInstance().execute(()->{
                    try{
                        var data=JsonParser.parseString(request).getAsJsonObject();var p=pending.remove(UUID.fromString(data.get("requestId").getAsString()));
                        if(p==null)throw new IllegalStateException("STALE_VIEW");
                        if(p.browser!=browser||!p.shellUrl.equals(url)||!url.equals(browser.getURL())||!p.current.getAsBoolean()){
                            p.result.completeExceptionally(new IllegalStateException("STALE_VIEW"));throw new IllegalStateException("STALE_VIEW");
                        }
                        var result=data.getAsJsonObject("result");String status=result.get("status").getAsString();
                        if(!status.equals("CAPTURE_REGION")){p.result.completeExceptionally(new IllegalStateException(status));callback.success("{}");return;}
                        if(!p.view.equals(result.get("viewId").getAsString())||!p.url.equals(result.get("frameUrl").getAsString())){p.result.completeExceptionally(new IllegalStateException("STALE_VIEW"));callback.failure(409,"STALE_VIEW");return;}
                        p.result.complete(result);callback.success("{}");
                    }catch(RuntimeException invalid){callback.failure(409,"CAPTURE_REGION_INVALID");}
                });return true;
            }
        },true);MCEF.getClient().getHandle().addMessageRouter(router);
    }
    static CompletableFuture<Captured> capture(String view,String observation,BooleanSupplier current){
        requireClient();
        if(WebGuiPopupCompositor.blockingCapture())return CompletableFuture.failedFuture(new IllegalStateException("VIEW_POPUP_ACTIVE"));
        if(active!=null)return CompletableFuture.failedFuture(new IllegalStateException("CAPTURE_BUSY"));
        if(!current.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("STALE_VIEW"));
        var observed=JsonParser.parseString(observation).getAsJsonObject();String document=observed.get("documentId").getAsString();
        if(!observed.get("status").getAsString().equals("OBSERVED"))return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        if(observed.has("sensitiveVisible")&&observed.get("sensitiveVisible").getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("SENSITIVE_VIEW"));
        for(var e:observed.getAsJsonArray("elements")){var element=e.getAsJsonObject();if(element.get("secret").getAsBoolean()&&element.get("visible").getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("SENSITIVE_VIEW"));}
        String token=UUID.randomUUID().toString().replace("-","").substring(0,24);
        var result=new CompletableFuture<Captured>();active=result;
        measure(view,token,current).thenCompose(region->{
            var viewport=observed.getAsJsonObject("viewport");
            if(Math.abs(viewport.get("width").getAsDouble()-region.get("frameWidth").getAsDouble())>1||Math.abs(viewport.get("height").getAsDouble()-region.get("frameHeight").getAsDouble())>1)
                return CompletableFuture.<Captured>failedFuture(new IllegalStateException("STALE_VIEW"));
            return pixels(region,token,current,result,0).thenCompose(bytes->onClient(()->measure(view,token,current).thenApply(after->{
                if(!current.getAsBoolean()||!region.get("layoutIdentity").equals(after.get("layoutIdentity")))throw new IllegalStateException("STALE_VIEW");
                var rect=region(region);var renderer=WebGuiHostAdapter.INSTANCE.browser().getRenderer();var p=rect.pixels(renderer.getTextureWidth(),renderer.getTextureHeight(),MAX_READ_PIXELS);
                var output=dev.mineagent.runtime.client.webui.UiCaptureSizing.fit(p.width(),p.height(),dev.mineagent.runtime.api.model.ModelImage.MAX_PIXELS);
                double sx=renderer.getTextureWidth()/rect.hostWidth(),sy=renderer.getTextureHeight()/rect.hostHeight();
                return new Captured(document,region.get("layoutIdentity").getAsString(),output.width(),output.height(),
                        region.get("frameX").getAsDouble()+(p.x()/sx-rect.x()),region.get("frameY").getAsDouble()+(p.y()/sy-rect.y()),sx*output.width()/p.width(),sy*output.height()/p.height(),bytes);
            })));
        }).whenComplete((value,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(value);});
        result.orTimeout(5,TimeUnit.SECONDS).whenComplete((v,e)->Minecraft.getInstance().execute(()->{
            if(active==result){active=null;var host=WebGuiHostAdapter.INSTANCE;if(host.ready())host.browser().executeJavaScript("document.getElementById('mineagent-capture-sync')?.remove();",host.browser().getURL(),0);}
        }));
        return result;
    }
    private static CompletableFuture<JsonObject> measure(String view,String token,BooleanSupplier current){
        requireClient();var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);
        if(!host.ready()||url==null||!host.packageLoaded(view)||!current.getAsBoolean()||pending.size()>=2)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        var result=new CompletableFuture<JsonObject>();UUID id=UUID.randomUUID();var browser=host.browser();
        pending.put(id,new Pending(browser,browser.getURL(),view,url,current,result));
        browser.executeJavaScript("window.__mineagentCaptureRegion("+JSON.toJson(id.toString())+","+JSON.toJson(view)+","+JSON.toJson(token)+");",browser.getURL(),0);
        result.orTimeout(1500,TimeUnit.MILLISECONDS).whenComplete((v,e)->Minecraft.getInstance().execute(()->pending.remove(id)));
        return result;
    }
    private static CompletableFuture<byte[]> pixels(JsonObject region,String token,BooleanSupplier current,CompletableFuture<Captured> owner,int attempt){
        requireClient();if(WebGuiPopupCompositor.blockingCapture())return CompletableFuture.failedFuture(new IllegalStateException("VIEW_POPUP_ACTIVE"));if(owner.isDone()||!current.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("STALE_VIEW"));
        var renderer=WebGuiHostAdapter.INSTANCE.browser().getRenderer();var texture=renderer.getTexture();
        if(texture==null||texture.isClosed()||texture.getFormat()!=TextureFormat.RGBA8)return CompletableFuture.failedFuture(new IllegalStateException("CAPTURE_TEXTURE_UNAVAILABLE"));
        var rect=region(region);int tw=renderer.getTextureWidth(),th=renderer.getTextureHeight();var p=rect.pixels(tw,th,MAX_READ_PIXELS);
        double sx=tw/rect.hostWidth(),sy=th/rect.hostHeight();int markerWidth=(int)Math.ceil(16*sx),markerHeight=(int)Math.ceil(4*sy),prefix=markerWidth*markerHeight*4;
        if(p.x()<markerWidth&&p.y()<markerHeight)return CompletableFuture.failedFuture(new IllegalStateException("CAPTURE_REGION_INVALID"));
        var data=new CompletableFuture<byte[]>();var encoder=RenderSystem.getDevice().createCommandEncoder();
        var buffer=RenderSystem.getDevice().createBuffer(()->"MineAgent authorized view ROI",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,(long)prefix+p.width()*p.height()*4L);
        try{
            encoder.copyTextureToBuffer(texture,buffer,0,()->{},0,0,0,markerWidth,markerHeight);
            encoder.copyTextureToBuffer(texture,buffer,prefix,()->{
                try(buffer){
                    if(WebGuiPopupCompositor.blockingCapture())throw new IllegalStateException("VIEW_POPUP_ACTIVE");
                    if(owner.isDone()||!current.getAsBoolean()||texture.isClosed()||renderer.getTexture()!=texture||renderer.getTextureWidth()!=tw||renderer.getTextureHeight()!=th)throw new IllegalStateException("STALE_VIEW");
                    try(var read=encoder.mapBuffer(buffer,true,false)){
                        var rgba=read.data();
                        for(int i=0;i<4;i++){
                            int offset=4*((int)Math.floor((2+4*i)*sx)+(int)Math.floor(2*sy)*markerWidth);
                            for(int channel=0;channel<3;channel++)if((rgba.get(offset+channel)&255)!=Integer.parseInt(token.substring(i*6+channel*2,i*6+channel*2+2),16))throw new IllegalStateException("CAPTURE_PAINT_PENDING");
                        }
                        byte[] bytes=new byte[p.width()*p.height()*4];rgba.position(prefix);rgba.get(bytes);data.complete(bytes);
                    }
                }catch(Exception failed){data.completeExceptionally(failed);}
            },0,p.x(),p.y(),p.width(),p.height());
        }catch(Exception failed){buffer.close();data.completeExceptionally(failed);}
        owner.whenComplete((v,e)->{if(!data.isDone())Minecraft.getInstance().execute(()->{if(!buffer.isClosed())buffer.close();data.completeExceptionally(new IllegalStateException("USER_INTERRUPTED"));});});
        return data.handle((rgba,error)->{
            if(error!=null){
                if("CAPTURE_PAINT_PENDING".equals(root(error).getMessage())&&attempt<12)return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(50,TimeUnit.MILLISECONDS))
                        .thenCompose(ignored->onClient(()->pixels(region,token,current,owner,attempt+1)));
                return CompletableFuture.<byte[]>failedFuture(error);
            }
            return CompletableFuture.supplyAsync(()->encode(rgba,p.width(),p.height()),ENCODER);
        }).thenCompose(value->value);
    }
    private static byte[] encode(byte[] rgba,int width,int height){
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int i=4*(y*width+x);image.setRGB(x,y,((rgba[i+3]&255)<<24)|((rgba[i]&255)<<16)|((rgba[i+1]&255)<<8)|(rgba[i+2]&255));}
        var size=dev.mineagent.runtime.client.webui.UiCaptureSizing.fit(width,height,dev.mineagent.runtime.api.model.ModelImage.MAX_PIXELS);
        if(size.width()!=width||size.height()!=height){var scaled=new BufferedImage(size.width(),size.height(),BufferedImage.TYPE_INT_ARGB);var graphics=scaled.createGraphics();
            try{graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(image,0,0,size.width(),size.height(),null);}finally{graphics.dispose();image.flush();}image=scaled;}
        try(var output=new ByteArrayOutputStream();var bounded=new FilterOutputStream(output){
            int count;private void reserve(int amount)throws IOException{if((long)count+amount>2_097_152)throw new IOException("CAPTURE_PNG_BUDGET");count+=amount;}
            @Override public void write(int b)throws IOException{reserve(1);out.write(b);}
            @Override public void write(byte[] b,int offset,int length)throws IOException{reserve(length);out.write(b,offset,length);}
        }){if(!ImageIO.write(image,"png",bounded))throw new IOException("CAPTURE_PNG_ENCODER");return output.toByteArray();}
        catch(IOException failed){throw new IllegalStateException("CAPTURE_PNG_FAILED",failed);}finally{image.flush();}
    }
    private static UiCaptureRegion region(JsonObject value){var r=value.getAsJsonObject("rect");return new UiCaptureRegion(r.get("x").getAsDouble(),r.get("y").getAsDouble(),r.get("width").getAsDouble(),r.get("height").getAsDouble(),value.get("hostWidth").getAsDouble(),value.get("hostHeight").getAsDouble());}
    private static Throwable root(Throwable e){for(int i=0;i<8&&e.getCause()!=null;i++)e=e.getCause();return e;}
    private static void requireClient(){if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("CLIENT_THREAD_REQUIRED");}
    private static <T> CompletableFuture<T> onClient(java.util.function.Supplier<CompletableFuture<T>> work){var result=new CompletableFuture<T>();Minecraft.getInstance().execute(()->{try{work.get().whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(v);});}catch(Exception e){result.completeExceptionally(e);}});return result;}
    public static void clear(){requireClient();var copy=List.copyOf(pending.values());pending.clear();copy.forEach(p->p.result.completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED")));if(active!=null)active.completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED"));}
}
