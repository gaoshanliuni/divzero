package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.mineagent.runtime.client.webui.NativeAtlasFrame;
import dev.mineagent.runtime.client.webui.NativeAtlasInputMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Render-only atlas bring-up. Not enabled by a page or by a presentation request. */
public final class WebGuiAtlasCompositor {
    private static final LinkedHashMap<String,NativeAtlasFrame> frames=new LinkedHashMap<>();private static boolean active;private static double ratio=1;private static long draws;private static String error="",lastDrawToken="";private static long lastDrawSequence;private static int screenW,screenH;
    private static boolean configured;
    private static final Map<String,NativeAtlasInputMap> inputFrames=new HashMap<>();
    public record Plan(NativeAtlasFrame frame,NativeAtlasInputMap input){}
    private WebGuiAtlasCompositor(){}
    public static boolean enabled(){return configured||Boolean.getBoolean("mineagent.nativeAtlasSmoke");}
    public static void configure(java.nio.file.Path path)throws java.io.IOException{configured=dev.mineagent.runtime.client.webui.WebGuiRendererSettings.liveAtlas(path);}
    public static boolean automatic(){return configured;}
    public static boolean active(){return enabled()&&active;}
    public static Map<String,Object> resize(JsonObject n){
        if(!enabled())throw new SecurityException("ATLAS_EXPERIMENT_NOT_ENABLED");
        int w=n.get("width").getAsInt(),h=n.get("height").getAsInt(),sw=n.get("screenWidth").getAsInt(),sh=n.get("screenHeight").getAsInt();double r=n.get("ratio").getAsDouble();var window=Minecraft.getInstance().getWindow();
        long pw=Math.round(w*r),ph=Math.round(h*r);
        if(!Double.isFinite(r)||r<.5||r>4||w<37||h<4||pw<1||ph<1||pw>8192||ph>8192||pw*ph>16_777_216||Math.abs(sw*r-window.getWidth())>2||Math.abs(sh*r-window.getHeight())>2)throw new IllegalArgumentException("ATLAS_RESIZE_BUDGET");
        ratio=r;screenW=window.getWidth();screenH=window.getHeight();
        if(!active){
            // Block new acquisition before cancellation callbacks run; release against the old viewport.
            active=true;PackagePageAgent.interruptControls();UiAgentClient.interruptAll();WebGuiNativeInput.cancelNative();
            if(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen screen)screen.releasePressed();
        }
        WebGuiHostAdapter.INSTANCE.browser().resize((int)pw,(int)ph);return Map.of("status","NATIVE_ATLAS_RESIZED","width",pw,"height",ph,"inputAvailable",inputAvailable());
    }
    private static NativeAtlasFrame.Rect rect(JsonObject n){return new NativeAtlasFrame.Rect(n.get("x").getAsDouble(),n.get("y").getAsDouble(),n.get("width").getAsDouble(),n.get("height").getAsDouble());}
    public static Plan parse(JsonObject n){
        if(!active())throw new SecurityException("ATLAS_NOT_ACTIVE");var layers=new ArrayList<NativeAtlasFrame.Surface>();for(var item:n.getAsJsonArray("surfaces")){var s=item.getAsJsonObject();layers.add(new NativeAtlasFrame.Surface(s.get("id").getAsString(),rect(s.getAsJsonObject("source")),rect(s.getAsJsonObject("destination")),s.get("opacity").getAsDouble(),s.get("z").getAsInt()));}
        var frame=new NativeAtlasFrame(UUID.fromString(n.get("documentId").getAsString()),n.get("revision").getAsLong(),n.get("token").getAsString(),n.get("width").getAsInt(),n.get("height").getAsInt(),n.get("screenWidth").getAsInt(),n.get("screenHeight").getAsInt(),layers);
        var hits=new ArrayList<NativeAtlasInputMap.Region>();if(n.has("hitRegions"))for(var item:n.getAsJsonArray("hitRegions")){var h=item.getAsJsonObject();hits.add(new NativeAtlasInputMap.Region(h.get("surfaceId").getAsString(),rect(h.getAsJsonObject("source"))));}
        return new Plan(frame,new NativeAtlasInputMap(frame,hits));
    }
    static void register(Plan p){var f=p.frame();frames.put(f.token(),f);inputFrames.put(f.token(),p.input());while(frames.size()>32){String old=frames.keySet().iterator().next();frames.remove(old);inputFrames.remove(old);}}
    public static boolean inputAvailable(){return active()&&(configured||Boolean.getBoolean("mineagent.nativeAtlasInputSmoke"));}
    public static NativeAtlasInputMap inputMap(){
        var m=WebGuiPaintComposition.matched().orElseThrow(()->new IllegalStateException("VIEW_NOT_RENDERED"));
        var map=inputFrames.get(m.frame().token());if(!active()||map==null||!lastDrawToken.equals(m.frame().token()))throw new IllegalStateException("VIEW_NOT_RENDERED");return map;
    }
    public static String captureIdentity(String view,boolean interactive){return active()?inputMap().identity(view,interactive):"";}
    public static boolean captureCurrent(String view,String proof,boolean interactive){try{return captureIdentity(view,interactive).equals(proof);}catch(RuntimeException stale){return false;}}
    public static boolean inputTokenCurrent(String token){try{return inputAvailable()&&inputMap().frame().token().equals(token);}catch(RuntimeException stale){return false;}}
    public record Route(NativeAtlasInputMap.Point point,int x,int y,int width,int height,String token,String identity,boolean held){
        public dev.mineagent.runtime.client.webui.ManagedInputDispatcher.TargetRequest request(String kind){return new dev.mineagent.runtime.client.webui.ManagedInputDispatcher.TargetRequest(kind,x,y,width,height,token,point.viewId(),held);}
    }
    public static Optional<Route> route(double x,double y,NativeAtlasInputMap.Point gesture){
        if(!inputAvailable())throw new IllegalStateException("ATLAS_RENDER_ONLY_INPUT_DISABLED");var map=inputMap();var f=map.frame();var window=Minecraft.getInstance().getWindow();
        double sx=x*f.screenWidth()/window.getWidth(),sy=y*f.screenHeight()/window.getHeight();var point=gesture==null?map.pointer(sx,sy):Optional.of(map.held(gesture,sx,sy));
        var paint=WebGuiPaintComposition.matched().orElseThrow();return point.map(p->new Route(p,(int)Math.round(p.sourceX()*paint.textureWidth()/f.width()),(int)Math.round(p.sourceY()*paint.textureHeight()/f.height()),paint.textureWidth(),paint.textureHeight(),f.token(),map.identity(p.viewId(),false),gesture!=null));
    }
    public static boolean routeCurrent(Route r){return inputTokenCurrent(r.token())&&captureCurrent(r.point().viewId(),r.identity(),false);}
    public static boolean worldAt(double x,double y){try{return inputAvailable()&&route(x,y,null).isEmpty();}catch(RuntimeException pending){return false;}}
    public static boolean render(GuiGraphicsExtractor graphics,RenderPipeline pipeline,Identifier texture,int width,int height){
        if(!active())return configured;
        try{var matched=WebGuiPaintComposition.matched();if(matched.isEmpty())return true;var m=matched.get();var frame=frames.get(m.frame().token());if(frame==null||!frame.documentId().equals(m.frame().documentId()))return true;
            var window=Minecraft.getInstance().getWindow();if(window.getWidth()!=screenW||window.getHeight()!=screenH)return true;
            for(var d:frame.draws(width,height,m.textureWidth(),m.textureHeight()))graphics.blit(pipeline,texture,d.x(),d.y(),(float)d.sourceX(),(float)d.sourceY(),d.width(),d.height(),d.sourceWidth(),d.sourceHeight(),m.textureWidth(),m.textureHeight(),d.argb());draws++;lastDrawToken=frame.token();lastDrawSequence=m.paintSequence();error="";WebGuiPopupCompositor.render(graphics,pipeline,width,height);
        }catch(Exception failed){error=failed.getClass().getSimpleName();}return true;
    }
    public static Map<String,Object> presentationPaint(String view,String document,dev.mineagent.runtime.core.ui.UiPresentationAction.Bounds b,Double wanted){
        try{var map=inputMap();var f=map.frame();if(!f.documentId().toString().equals(document))return Map.of("status","PENDING");var surface=f.surfaces().stream().filter(x->x.id().equals(view)).findFirst().orElseThrow();var d=surface.destination();
            if(wanted!=null&&Double.compare(wanted,surface.opacity())!=0||Math.abs(d.x()+16-b.x())>1||Math.abs(d.y()+16-b.y())>1||Math.abs(d.width()-32-b.width())>1||Math.abs(d.height()-32-b.height())>1)return Map.of("status","PENDING");
            return Map.of("status","NATIVE_ATLAS_PAINTED","hostDocumentId",document,"viewId",view,"token",f.token(),"paintSequence",lastDrawSequence,"opacity",surface.opacity(),"alpha",Math.round(surface.opacity()*255),"targetDrawSkipped",Math.round(surface.opacity()*255)==0);
        }catch(RuntimeException pending){return Map.of("status","PENDING");}
    }
    public static void tick(){if(!active())return;var w=Minecraft.getInstance().getWindow();if(w.getWidth()!=screenW||w.getHeight()!=screenH){screenW=w.getWidth();screenH=w.getHeight();WebGuiHostAdapter.INSTANCE.emit("nativeAtlasViewport",Map.of("width",Math.round(screenW/ratio),"height",Math.round(screenH/ratio)));}}
    public static Map<String,Object> snapshot(){var n=new LinkedHashMap<String,Object>();n.put("active",active());n.put("draws",draws);n.put("error",error);n.put("inputAvailable",inputAvailable());n.put("lastDrawToken",lastDrawToken);n.put("lastDrawSequence",lastDrawSequence);n.put("inputRegions",WebGuiPaintComposition.matched().map(m->inputFrames.get(m.frame().token())).map(NativeAtlasInputMap::regions).orElse(List.of()));n.put("frame",WebGuiPaintComposition.matched().map(m->frames.get(m.frame().token())).orElse(null));return n;}
    public static void clear(){frames.clear();inputFrames.clear();active=false;draws=0;error="";lastDrawToken="";lastDrawSequence=0;}
}
