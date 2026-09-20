package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.client.webui.NativeAtlasFrame;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Live raster/compositor experiment only; does not claim native input or presentation-tool completion. */
public final class NativeAtlasSmokeClient {
    private static final Gson JSON=new Gson();private static int phase,ticks,after;private static boolean busy,done;private static String lower,upper;private static JsonObject oldUpper;private static int opaqueUpper,opaqueLower;private static boolean previousControlInterrupted;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.nativeAtlasSmoke");}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("native-atlas-evidence");}
    private static void write(String name,Object data)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(data));}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static String page(String title,String color,int x,int y)throws Exception{
        String html="<!doctype html><html><meta charset='utf-8'><body style='margin:0;padding:12px;background:"+color+";color:white;font:16px sans-serif'><h2>"+title+"</h2><p>Live DOM counter <b id='counter'>0</b></p><input id='draft' value='UNCHANGED_DRAFT'><script>let n=0;setInterval(()=>document.querySelector('#counter').textContent=++n,100);</script></body></html>";
        String settings="{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"TOP_LEFT\",\"width\":360,\"height\":280,\"offsetX\":"+x+",\"offsetY\":"+y+",\"appearance\":\"PACKAGE\"}}}";
        var refs=new LinkedHashMap<String,RuntimeResourceRef>();var blobs=new HashMap<String,byte[]>();for(var item:Map.of("ui/index.html",html,"ui/view-settings.json",settings).entrySet()){byte[] bytes=item.getValue().getBytes(StandardCharsets.UTF_8);String hash=dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(bytes);blobs.put(hash,bytes);refs.put(item.getKey(),new RuntimeResourceRef(item.getKey(),hash,RuntimeResourceSide.CLIENT,item.getKey().endsWith("html")?"text/html":"application/json",bytes.length));}
        var entry=refs.get("ui/index.html");var p=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,title,"1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("ui",new RuntimeEntrypoint(entry.path(),entry.side(),entry.sha256())),Map.of(),refs,PackageOrigin.LOCAL_STUDIO,false,1,entry.sha256(),"LOCAL_VISUAL_FIXTURE_NOT_MODEL",0);
        return WebGuiHostAdapter.INSTANCE.openPackagePreview(p,blobs::get,entry.path(),false);
    }
    private static NativeAtlasFrame frame(){return (NativeAtlasFrame)WebGuiAtlasCompositor.snapshot().get("frame");}
    private static NativeAtlasFrame.Surface surface(String id){return frame().surfaces().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}
    private static boolean painted(double opacity){var f=frame();if(f==null)return false;var snap=WebGuiAtlasCompositor.snapshot();return f.token().equals(snap.get("lastDrawToken"))&&f.surfaces().stream().anyMatch(s->s.id().equals(upper)&&s.opacity()==opacity)&&f.surfaces().stream().anyMatch(s->s.id().equals(lower)&&s.opacity()==1);}
    private static int pixel(java.awt.image.BufferedImage image,double x,double y){var f=frame();return image.getRGB((int)Math.round(x*image.getWidth()/f.screenWidth()),(int)Math.round(y*image.getHeight()/f.screenHeight()))&0xffffff;}
    private static void same(int actual,int expected,int tolerance,String code){for(int shift:new int[]{0,8,16})require(Math.abs(((actual>>shift)&255)-((expected>>shift)&255))<=tolerance,code+"_"+Integer.toHexString(actual)+"_"+Integer.toHexString(expected));}
    private static int blend(int a,int b){int result=0;for(int shift:new int[]{0,8,16})result|=(int)Math.round(((a>>shift)&255)*(128.0/255)+((b>>shift)&255)*(127.0/255))<<shift;return result;}
    private static void screenshot(String name,int next,double opacity){busy=true;var mc=Minecraft.getInstance();var lo=surface(lower).destination();var up=surface(upper).destination();
        net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){Path file=root().resolve(name+".png");image.writeToFile(file);mc.execute(()->{try{var png=javax.imageio.ImageIO.read(file.toFile());int lowerPixel=pixel(png,lo.x()+16+35,lo.y()+16+150),overlap=pixel(png,up.x()+16+60,up.y()+16+150);
            if(opacity==1){opaqueUpper=overlap;opaqueLower=lowerPixel;same(opaqueUpper,0x2050d0,5,"ATLAS_UPPER_OPAQUE");same(opaqueLower,0xd02020,5,"ATLAS_LOWER_OPAQUE");}
            else{same(lowerPixel,opaqueLower,2,"ATLAS_CHANGED_SIBLING");same(overlap,opacity==0?opaqueLower:blend(opaqueUpper,opaqueLower),4,"ATLAS_BLEND_NOT_INDEPENDENT");}
            write(name+".json",Map.of("native",WebGuiAtlasCompositor.snapshot(),"lowerPixel",lowerPixel,"overlapPixel",overlap,"opacity",opacity,"systemInputInjected",false));phase=next;after=ticks+20;busy=false;
        }catch(Exception e){fail(e);}});}catch(Exception e){mc.execute(()->fail(e));}});
    }
    public static void tick(boolean trusted)throws Exception{
        if(done)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Files.createDirectories(root());
        try{
            require(ticks<2400,"ATLAS_SMOKE_TIMEOUT_"+phase);if(!trusted)return;
            if(phase==0){host.open();phase=1;}
            if(phase==1&&host.ready()&&UiClientSessions.current()!=null){lower=page("Lower live red","#d02020",100,20);upper=page("Upper live blue","#2050d0",200,80);phase=2;after=ticks+60;}
            if(phase==2&&ticks>=after&&!busy&&host.packageLoaded(lower)&&host.packageLoaded(upper)){busy=true;PackagePageAgent.inspectManagedView(upper).whenComplete((text,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);oldUpper=JsonParser.parseString(text).getAsJsonObject();write("before-atlas.json",oldUpper);var previous=PackagePageAgent.controlPreview(lower);previous.onInterrupt(()->previousControlInterrupted=true);host.emit("nativeAtlasStart",Map.of());phase=3;busy=false;}catch(Exception e){fail(e);}}));}
            if(phase==3&&frame()!=null&&frame().surfaces().stream().anyMatch(s->s.id().equals(upper))){verifyRenderOnlySafety();host.emit("nativeAtlasOpacity",Map.of("viewId",lower,"opacity",1));host.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",1));phase=4;after=ticks+30;}
            if(phase==4&&ticks>=after&&!busy&&painted(1))screenshot("opaque",5,1);
            if(phase==5&&ticks>=after){host.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",.5));phase=6;after=ticks+30;}
            if(phase==6&&ticks>=after&&!busy&&painted(.5))screenshot("half",7,.5);
            if(phase==7&&ticks>=after){host.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",0));phase=8;after=ticks+30;}
            if(phase==8&&ticks>=after&&!busy&&painted(0))screenshot("zero",9,0);
            if(phase==9&&ticks>=after&&!busy){busy=true;PackagePageAgent.inspectManagedView(upper).whenComplete((text,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);var current=JsonParser.parseString(text).getAsJsonObject();write("after-zero-live-dom.json",current);require(current.get("documentId").equals(oldUpper.get("documentId")),"ATLAS_RELOADED_PAGE");require(!current.get("visibleText").getAsString().equals(oldUpper.get("visibleText").getAsString()),"ATLAS_FROZEN_DOM");write("after-zero-live-dom.json",current);phase=10;busy=false;}catch(Exception e){fail(e);}}));}
            if(phase==10&&!busy){busy=true;PackagePageAgent.captureManagedView(upper).whenComplete((shot,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.write(root().resolve("zero-opacity-private.png"),shot.png());var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(shot.png()));int blue=0;for(int y=0;y<png.getHeight();y+=5)for(int x=0;x<png.getWidth();x+=5)if((png.getRGB(x,y)&0xffffff)==0x2050d0)blue++;require(blue>100,"ATLAS_PRIVATE_LOST_SOURCE_COLOR");write("result.json",Map.of("status","LIVE_NATIVE_ATLAS_BLEND_VERIFIED_RENDER_ONLY","lower",lower,"upper",upper,"privateBlueSamples",blue,"inputVerified",false,"presentationToolsEnabled",false,"modelCalls",0,"systemInputInjected",false,"fullV1",false));done=true;host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_NATIVE_ATLAS_OK renderOnly=true");dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_OK nativeAtlasEnvironmentOnly=true");}catch(Exception e){fail(e);}}));}
        }catch(Exception e){fail(e);}
    }
    private static void verifyRenderOnlySafety()throws Exception{
        require(previousControlInterrupted,"ATLAS_OLD_CONTROL_NOT_INTERRUPTED");
        for(boolean delegated:new boolean[]{false,true}){
            try{
                if(delegated)PackagePageAgent.controlDelegated(upper,UUID.randomUUID());else PackagePageAgent.controlPreview(upper);
                throw new IllegalStateException("ATLAS_CONTROL_WAS_GRANTED");
            }catch(SecurityException denied){require("ATLAS_AGENT_INPUT_NOT_AVAILABLE".equals(denied.getMessage()),"ATLAS_WRONG_DENIAL");}
        }
        var before=frame();var attempted=JSON.toJsonTree(WebGuiPaintComposition.matched().orElseThrow().frame()).getAsJsonObject();
        attempted.addProperty("atlas",true);attempted.addProperty("screenWidth",before.screenWidth());attempted.addProperty("screenHeight",before.screenHeight());attempted.addProperty("revision",before.revision()+1);
        attempted.add("surfaces",JSON.toJsonTree(before.surfaces()));for(var item:attempted.getAsJsonArray("surfaces"))if(item.getAsJsonObject().get("id").getAsString().equals(upper))item.getAsJsonObject().addProperty("opacity",0);
        boolean rejected=false;try{WebGuiPaintComposition.acceptPlan(attempted);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected&&before.equals(frame()),"ATLAS_REJECTED_PLAN_MUTATED_FRAME");
        write("render-only-safety.json",Map.of("oldControlInterrupted",true,"previewRejected",true,"delegatedRejected",true,"rejectedPlanKeptFrame",true,"systemInputInjected",false,"pressedReleaseNativeVerified",false));
    }
    private static void fail(Exception e){if(done)return;done=true;try{write("failure.json",Map.of("phase",phase,"error",e.toString(),"native",WebGuiAtlasCompositor.snapshot()));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("NATIVE_ATLAS_SMOKE_FAILED",e);}
}
