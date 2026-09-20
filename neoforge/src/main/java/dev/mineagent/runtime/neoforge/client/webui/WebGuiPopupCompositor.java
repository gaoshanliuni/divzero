package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.*;
import com.google.gson.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.mineagent.runtime.client.webui.*;
import dev.mineagent.runtime.api.ui.ReadOnlyUiLease;
import dev.mineagent.runtime.api.ui.UiProtocol.Session;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.lwjgl.system.MemoryUtil;
import java.awt.Rectangle;
import java.awt.Point;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** CEF popup raster and input ownership; never writes the popup into a managed atlas source texture. */
public final class WebGuiPopupCompositor {
    private static final Gson JSON=new Gson();private static CefMessageRouter router;
    private record Intent(MCEFBrowser browser,String view,String url,Session session,UUID document,long until,double dx,double dy){}
    private record Query(CefBrowser browser,long frame,String url,long epoch,CompletableFuture<JsonObject> result){}
    private static volatile Intent intent;
    private static final Map<UUID,Query> queries=new HashMap<>();
    private static long epoch,shownAt,dismissedAt,paints,draws,closed,denied;private static boolean visible,captureBlocked,invalid,verified,openingRelease;
    private static Rectangle nativeRect;private static NativePopupFrame popup;private static String identity="",error="";private static Intent owner;
    private static JsonObject rootProof,controlProof;private static ByteBuffer pending;private static int pendingWidth,pendingHeight;
    private static PopupRenderer renderer;private static boolean mouseOpening;private static int openingX,openingY;
    private static final class PopupRenderer extends MCEFRenderer {PopupRenderer(){super(true);}void upload(ByteBuffer p,int w,int h){onPaint(p,w,h);}void dispose(){cleanup();}}
    private WebGuiPopupCompositor(){}
    public static boolean visible(){return visible;}
    public static boolean blockingCapture(){return captureBlocked;}
    public static long epoch(){return epoch;}
    public static boolean ownsTexture(GpuTexture texture){return renderer!=null&&renderer.getTexture()==texture;}
    public static void register(){
        if(router!=null)return;router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentPopupQuery","mineagentPopupQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter(){@Override public boolean onQuery(CefBrowser b,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback cb){
            if(persistent||request==null||request.length()>4096||frame==null||!WebGuiHostAdapter.INSTANCE.owns(b)){cb.failure(403,"POPUP_SOURCE");return true;}
            long fid=frame.getIdentifier();String url=frame.getURL();Minecraft.getInstance().execute(()->{try{var value=JsonParser.parseString(request).getAsJsonObject();var q=queries.remove(UUID.fromString(value.get("id").getAsString()));if(q==null||q.browser()!=b||q.frame()!=fid||!q.url().equals(url)||q.epoch()!=epoch||!visible){cb.failure(409,"POPUP_STALE");return;}q.result().complete(value.getAsJsonObject("value"));cb.success("{}");}catch(RuntimeException failed){cb.failure(400,"POPUP_PROBE_INVALID");}});return true;
        }},true);MCEF.getClient().getHandle().addMessageRouter(router);
    }
    public static void notePointer(WebGuiAtlasCompositor.Route route){noteTarget(route.point().viewId());mouseOpening=true;openingX=route.x();openingY=route.y();}
    public static void baseRelease(){mouseOpening=false;}
    public static void noteTarget(String view){
        mouseOpening=false;
        if(!WebGuiAtlasCompositor.inputAvailable()||visible)return;
        try{String id=view.isEmpty()?"chrome:atlas":view;var map=WebGuiAtlasCompositor.inputMap();var f=map.frame();var s=f.surfaces().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();if(Math.round(s.opacity()*255)==0)return;
            var m=WebGuiPaintComposition.matched().orElseThrow();var w=Minecraft.getInstance().getWindow();String url=WebGuiHostAdapter.INSTANCE.packageUrl(id);
            intent=new Intent(WebGuiHostAdapter.INSTANCE.browser(),id,url,PackageContentClient.rawSession(id),f.documentId(),System.nanoTime()+TimeUnit.SECONDS.toNanos(5),s.destination().x()*w.getWidth()/f.screenWidth()-s.source().x()*m.textureWidth()/f.width(),s.destination().y()*w.getHeight()/f.screenHeight()-s.source().y()*m.textureHeight()/f.height());
        }catch(RuntimeException unavailable){intent=null;}
    }
    public static Point screenPoint(CefBrowser browser,Point point){var i=intent;if(i==null||i.browser()!=browser||!visible&&System.nanoTime()>i.until())return null;return new Point((int)Math.round(point.x+i.dx()),(int)Math.round(point.y+i.dy()));}
    public static void shown(MCEFBrowser browser,boolean show){
        if(!WebGuiHostAdapter.INSTANCE.owns(browser))return;
        epoch++;visible=show;captureBlocked=true;invalid=false;verified=false;popup=null;identity="";owner=null;rootProof=null;controlProof=null;freePending();retire();
        var old=List.copyOf(queries.values());queries.clear();old.forEach(q->q.result().completeExceptionally(new IllegalStateException("POPUP_STALE")));
        if(!show){closed++;return;}shownAt=System.nanoTime();openingRelease=mouseOpening;error="";
        if(!WebGuiAtlasCompositor.active())return;
        var i=intent;if(i==null||i.browser()!=browser||System.nanoTime()>i.until()){dismiss("POPUP_WITHOUT_INPUT_OWNER");return;}owner=i;verifyOwner(epoch);
    }
    public static void size(MCEFBrowser browser,Rectangle size){if(WebGuiHostAdapter.INSTANCE.owns(browser))nativeRect=size==null?null:new Rectangle(size);}
    private static CompletableFuture<JsonObject> probe(CefFrame frame,String script,long generation){
        var b=WebGuiHostAdapter.INSTANCE.browser();var id=UUID.randomUUID();var result=new CompletableFuture<JsonObject>();String url=frame.getURL();if(queries.size()>=4)return CompletableFuture.failedFuture(new IllegalStateException("POPUP_QUERY_BUDGET"));
        queries.put(id,new Query(b,frame.getIdentifier(),url,generation,result));frame.executeJavaScript("(()=>{const value=(()=>{"+script+"})();window.mineagentPopupQuery({request:JSON.stringify({id:"+JSON.toJson(id.toString())+",value}),persistent:false,onSuccess(){},onFailure(){}});})();",url,0);
        result.orTimeout(1200,TimeUnit.MILLISECONDS).whenComplete((v,e)->Minecraft.getInstance().execute(()->queries.remove(id)));return result;
    }
    private static CefFrame frame(String url){var b=WebGuiHostAdapter.INSTANCE.browser();if(b==null)return null;for(long id:PackagePageAgent.frameIds(b.getFrameIdentifiers())){var f=b.getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL()))return f;}return null;}
    private static void verifyOwner(long generation){
        var i=owner;if(i==null)return;var browser=i.browser();
        probe(browser.getMainFrame(),"return window.__mineagentPopupOwner();",generation).thenCompose(root->{
            if(generation!=epoch||!visible)throw new IllegalStateException("POPUP_STALE");rootProof=root;
            if(!root.get("viewId").getAsString().equals(i.view())||!root.get("hostDocumentId").getAsString().equals(i.document().toString()))throw new IllegalStateException("POPUP_FOCUS_OWNER");
            if(root.get("tag").getAsString().equals("IFRAME")){if(i.url()==null||!root.get("url").getAsString().equals(i.url()))throw new IllegalStateException("POPUP_FRAME_OWNER");var f=frame(i.url());if(f==null)throw new IllegalStateException("POPUP_FRAME_OWNER");return probe(f,focusScript(),generation);}
            return CompletableFuture.completedFuture(root.getAsJsonObject("control"));
        }).whenComplete((control,failure)->Minecraft.getInstance().execute(()->{if(generation!=epoch||!visible)return;try{if(failure!=null)throw new IllegalStateException(failure);controlProof=control;
            if(!Set.of("SELECT","INPUT","TEXTAREA").contains(control.get("tag").getAsString())||control.get("secret").getAsBoolean()||!control.get("visible").getAsBoolean())throw new IllegalStateException("POPUP_CONTROL_OWNER");
            var map=WebGuiAtlasCompositor.inputMap();if(!rootProof.get("token").getAsString().equals(map.frame().token())){retryOwner(generation);return;}if(!map.frame().documentId().equals(i.document()))throw new IllegalStateException("POPUP_DOCUMENT_CHANGED");map.requireInteractive(i.view());var surface=map.frame().surfaces().stream().filter(s->s.id().equals(i.view())).findFirst().orElseThrow();var c=control.getAsJsonObject("rect");double x=c.get("x").getAsDouble(),y=c.get("y").getAsDouble(),w=c.get("width").getAsDouble(),h=c.get("height").getAsDouble();
            if(rootProof.get("tag").getAsString().equals("IFRAME")){var parent=rootProof.getAsJsonObject("frameRect");if(x< -1||y< -1||x+w>parent.get("width").getAsDouble()+1||y+h>parent.get("height").getAsDouble()+1)throw new IllegalStateException("POPUP_CONTROL_BOUNDS");x+=parent.get("x").getAsDouble();y+=parent.get("y").getAsDouble();}
            var src=surface.source();if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(w)||!Double.isFinite(h)||w<=0||h<=0||x<src.x()-1||y<src.y()-1||x+w>src.x()+src.width()+1||y+h>src.y()+src.height()+1)throw new IllegalStateException("POPUP_SOURCE_BOUNDS");identity=map.identity(i.view(),false);verified=true;
        }catch(Exception failed){if(System.nanoTime()-shownAt<TimeUnit.SECONDS.toNanos(1)&&rootProof!=null&&rootProof.get("viewId").getAsString().equals(i.view()))retryOwner(generation);else dismiss("POPUP_OWNER_REJECTED");}}));
    }
    private static void retryOwner(long generation){CompletableFuture.delayedExecutor(30,TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(()->{if(visible&&!invalid&&epoch==generation)verifyOwner(generation);}));}
    private static String focusScript(){return "const e=document.activeElement,r=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,secret:e.type==='password'||e.type==='file',visible:e.isConnected&&!e.disabled&&s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0,rect:{x:r.x,y:r.y,width:r.width,height:r.height}};";}
    public static void paint(MCEFBrowser browser,long generation,ByteBuffer pixels,int width,int height,Rectangle bounds){
        if(!visible||invalid||generation!=epoch||!WebGuiHostAdapter.INSTANCE.owns(browser))return;
        if(width<1||height<1||width>4096||height>4096||(long)width*height>2_097_152||bounds==null||bounds.width!=width||bounds.height!=height){dismiss("POPUP_PIXEL_GEOMETRY");return;}
        nativeRect=new Rectangle(bounds);freePending();pending=MemoryUtil.memAlloc(width*height*4);var input=pixels.duplicate();input.clear();input.limit(width*height*4);pending.put(input).flip();pendingWidth=width;pendingHeight=height;paints++;
    }
    public static void beginFrame(){
        if(!visible||invalid||!verified||pending==null)return;
        try{if(!currentOwner())throw new IllegalStateException("POPUP_OWNER_CHANGED");var map=WebGuiAtlasCompositor.inputMap();var m=WebGuiPaintComposition.matched().orElseThrow();
            var control=controlProof.getAsJsonObject("rect");double ox=0,oy=0;if(rootProof.get("tag").getAsString().equals("IFRAME")){var parent=rootProof.getAsJsonObject("frameRect");ox=parent.get("x").getAsDouble();oy=parent.get("y").getAsDouble();}
            double sx=m.textureWidth()/(double)map.frame().width(),sy=m.textureHeight()/(double)map.frame().height();double ax=(ox+control.get("x").getAsDouble())*sx,ay=(oy+control.get("y").getAsDouble())*sy,aw=control.get("width").getAsDouble()*sx,ah=control.get("height").getAsDouble()*sy;
            if(ax+aw<nativeRect.x-12||ay+ah<nativeRect.y-12||ax>nativeRect.x+nativeRect.width+12||ay>nativeRect.y+nativeRect.height+12)throw new IllegalStateException("POPUP_ANCHOR_MISMATCH");
            popup=NativePopupFrame.place(map.frame(),owner.view(),epoch,new NativeAtlasFrame.Rect(nativeRect.x,nativeRect.y,nativeRect.width,nativeRect.height),m.textureWidth(),m.textureHeight());
            if(renderer==null){renderer=new PopupRenderer();renderer.initialize();}renderer.upload(pending,pendingWidth,pendingHeight);freePending();
        }catch(RuntimeException failed){dismiss(failed.getMessage()!=null&&failed.getMessage().startsWith("POPUP_")?failed.getMessage():"POPUP_PAINT_REJECTED");}
    }
    private static boolean currentOwner(){return owner!=null&&WebGuiHostAdapter.INSTANCE.browser()==owner.browser()&&(!WebGuiHostAdapter.INSTANCE.packageHidden(owner.view()))&&Objects.equals(owner.url(),WebGuiHostAdapter.INSTANCE.packageUrl(owner.view()))&&ReadOnlyUiLease.sameContext(owner.session(),PackageContentClient.rawSession(owner.view()))&&WebGuiAtlasCompositor.captureCurrent(owner.view(),identity,false);}
    public static void basePaintApplied(boolean popupPaint,boolean shown){if(!popupPaint&&!shown&&!visible)captureBlocked=false;}
    public static void tick(){if(!visible)return;if(invalid&&System.nanoTime()-dismissedAt>TimeUnit.SECONDS.toNanos(2)){McefPaintBoundary.abortPopup(WebGuiHostAdapter.INSTANCE.browser());return;}if(WebGuiAtlasCompositor.active()&&(invalid||verified&&!currentOwner()))dismiss("POPUP_OWNER_CHANGED");if(!verified&&WebGuiAtlasCompositor.active()&&System.nanoTime()-shownAt>TimeUnit.SECONDS.toNanos(2))dismiss("POPUP_OWNER_TIMEOUT");}
    public static void render(GuiGraphicsExtractor graphics,RenderPipeline pipeline,int width,int height){
        if(!visible||invalid||!verified||popup==null||renderer==null||!renderer.isTextureReady()||!currentOwner())return;
        var f=WebGuiAtlasCompositor.inputMap().frame();var d=popup.destination();int x=(int)Math.round(d.x()*width/f.screenWidth()),y=(int)Math.round(d.y()*height/f.screenHeight());
        graphics.blit(pipeline,renderer.getTextureIdentifier(),x,y,0f,0f,Math.max(1,(int)Math.round(d.width()*width/f.screenWidth())),Math.max(1,(int)Math.round(d.height()*height/f.screenHeight())),renderer.getTextureWidth(),renderer.getTextureHeight(),renderer.getTextureWidth(),renderer.getTextureHeight(),popup.alpha()<<24|0x00ffffff);draws++;
    }
    public static boolean pointer(double x,double y,int button,String kind,double wheel){
        if(!WebGuiAtlasCompositor.active()||!visible)return false;long generation=epoch;var b=WebGuiHostAdapter.INSTANCE.browser();
        if(kind.equals("release")&&openingRelease){openingRelease=false;WebGuiNativeInput.afterInputs(()->{if(b!=null&&epoch==generation)b.sendMouseRelease(openingX,openingY,button);});return true;}
        if(!verified||invalid||popup==null)return true;
        var w=Minecraft.getInstance().getWindow();var f=WebGuiAtlasCompositor.inputMap().frame();var p=popup.point(x*f.screenWidth()/w.getWidth(),y*f.screenHeight()/w.getHeight());
        if(p.isEmpty()){if(kind.equals("press"))dismiss("POPUP_OUTSIDE_DISMISS");return true;}
        var point=p.get();WebGuiNativeInput.afterInputs(()->{if(epoch!=generation||!visible||invalid||!currentOwner())return;b.setFocus(true);switch(kind){case "press"->b.sendMousePress(point.x(),point.y(),button);case "release"->b.sendMouseRelease(point.x(),point.y(),button);case "motion"->b.sendMouseMove(point.x(),point.y());case "wheel"->b.sendMouseWheel(point.x(),point.y(),wheel,0);default->throw new IllegalArgumentException("POPUP_INPUT");}});return true;
    }
    public static boolean keyboard(Runnable send){if(!WebGuiAtlasCompositor.active()||!visible)return false;long generation=epoch;WebGuiNativeInput.afterInputs(()->{if(generation==epoch&&visible&&!invalid&&verified&&currentOwner())send.run();});return true;}
    public static void dismiss(String code){if(!visible)return;if(!invalid){denied++;error=code;dismissedAt=System.nanoTime();}invalid=true;verified=false;popup=null;freePending();retire();var b=WebGuiHostAdapter.INSTANCE.browser();if(b!=null)b.setFocus(false);}
    public static Map<String,Object> snapshot(){var n=new LinkedHashMap<String,Object>();n.put("visible",visible);n.put("captureBlocked",captureBlocked);n.put("verified",verified);n.put("epoch",epoch);n.put("paints",paints);n.put("draws",draws);n.put("closed",closed);n.put("denied",denied);n.put("error",error);n.put("frame",popup);n.put("nativeRect",nativeRect);n.put("rendererPresent",renderer!=null);n.put("pendingBytes",pending==null?0:pending.capacity());n.put("pendingQueries",queries.size());n.put("rootProof",rootProof);n.put("controlProof",controlProof);return n;}
    private static void freePending(){if(pending!=null){MemoryUtil.memFree(pending);pending=null;}}
    private static void retire(){if(renderer!=null){renderer.dispose();renderer=null;}}
    public static void clear(){epoch++;visible=captureBlocked=verified=invalid=false;owner=null;intent=null;popup=null;identity="";freePending();retire();var old=List.copyOf(queries.values());queries.clear();old.forEach(q->q.result().completeExceptionally(new IllegalStateException("POPUP_CLOSED")));}
}
