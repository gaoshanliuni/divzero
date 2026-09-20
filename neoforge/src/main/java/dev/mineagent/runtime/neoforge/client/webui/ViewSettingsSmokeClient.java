package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.neoforge.ui.WorldUiSmokeServer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
/** Native signed defaults/paint checks. The separate paint-only mode never enters the OS manual-override path. */
public final class ViewSettingsSmokeClient {
    private static final Gson JSON=new Gson();public static boolean finished;private static int phase,ticks,after,reopenTicks;private static boolean busy,left,reopened;private static String sibling;private static JsonObject before,manual;private static java.awt.Robot robot;
    private ViewSettingsSmokeClient(){}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.viewSettingsSmoke")||paintOnly();}
    private static boolean paintOnly(){return Boolean.getBoolean("mineagent.viewSettingsPaintSmoke");}
    private static JsonObject paintBaseline;
    private static String invalidPlanReply;
    public static void paintProbe(JsonObject value){if(paintOnly()&&value.has("paintLayoutSmoke"))invalidPlanReply=value.get("paintLayoutSmoke").getAsString();if(paintOnly()&&value.has("settingsDock"))try{save("reopen-dock-dom",value.get("settingsDock"));}catch(Exception e){fail(e);}}
    private static void main(String code){var b=WebGuiHostAdapter.INSTANCE.browser();b.executeJavaScript(code,b.getURL(),0);}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldUiSmokeServer.directory()).resolve("view-settings");}
    private static void save(String name,Object value)throws Exception{Files.writeString(root().resolve(name+".json"),JSON.toJson(value));}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static void frame(String view,String code){var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(code,url,0);return;}}throw new IllegalStateException("SETTINGS_FRAME_MISSING");}
    private static boolean sameBounds(JsonObject a,JsonObject b){for(String key:List.of("x","y","width","height"))if(Math.abs(a.getAsJsonObject("bounds").get(key).getAsDouble()-b.getAsJsonObject("bounds").get(key).getAsDouble())>1)return false;return true;}
    private static boolean sameAvailableArea(JsonObject a,JsonObject b){for(String group:List.of("area","viewport"))for(var entry:b.getAsJsonObject(group).entrySet())if(Math.abs(a.getAsJsonObject(group).get(entry.getKey()).getAsDouble()-entry.getValue().getAsDouble())>1)return false;return true;}
    private static boolean paintMatches(String view,JsonObject layout,String stage)throws Exception{
        var paint=JSON.toJsonTree(WebGuiPaintComposition.snapshot()).getAsJsonObject();
        if(!paint.get("status").getAsString().equals("PAINT_LAYOUT_MATCHED"))return false;
        var plan=paint.getAsJsonObject("frame");if(!plan.get("documentId").equals(layout.get("hostDocumentId")))return false;
        var viewport=layout.getAsJsonObject("viewport");if(!plan.get("width").equals(viewport.get("width"))||!plan.get("height").equals(viewport.get("height")))return false;
        for(var value:plan.getAsJsonArray("layers")){var layer=value.getAsJsonObject();if(!layer.get("id").getAsString().equals(view))continue;
            for(String key:List.of("x","y","width","height"))if(Math.abs(layer.get(key).getAsDouble()-layout.getAsJsonObject("bounds").get(key).getAsDouble())>1)return false;
            save(stage+"-paint-layout",paint);return true;
        }return false;
    }
    private static void verifyAnchor(JsonObject s){var area=s.getAsJsonObject("area");var b=s.getAsJsonObject("bounds");double expected=area.get("x").getAsDouble()+area.get("width").getAsDouble()-560-24;require(s.get("source").getAsString().equals("PACKAGE")&&Math.abs(b.get("x").getAsDouble()-expected)<1&&Math.abs(b.get("y").getAsDouble()-area.get("y").getAsDouble()-12)<1&&Math.abs(b.get("width").getAsDouble()-560)<1&&Math.abs(b.get("height").getAsDouble()-440)<1,"SETTINGS_ANCHOR_NOT_APPLIED");}
    private static void cursor(JsonObject s,double dx,double dy){var mc=Minecraft.getInstance();require(mc.isWindowActive(),"SETTINGS_GAME_NOT_FOCUSED");var b=s.getAsJsonObject("bounds");var v=s.getAsJsonObject("viewport");GLFW.glfwSetCursorPos(mc.getWindow().handle(),(b.get("x").getAsDouble()+70+dx)*mc.getWindow().getScreenWidth()/v.get("width").getAsDouble(),(b.get("y").getAsDouble()+20+dy)*mc.getWindow().getScreenHeight()/v.get("height").getAsDouble());}
    private static void style(String view,boolean custom,Runnable next){busy=true;frame(view,"let p=document.getElementById('settings-style-proof');if(!p){p=document.createElement('p');p.id='settings-style-proof';document.body.prepend(p);}p.textContent='SETTINGS_STYLE:'+getComputedStyle(document.body).backgroundColor+':'+(document.documentElement.getAttribute('data-mineagent-theme')||'PACKAGE');");
        PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{if(error!=null)throw new IllegalStateException(error);save(custom?"custom-style":"default-style",JsonParser.parseString(value));String text=JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString();require(text.contains(custom?"SETTINGS_STYLE:rgb(53, 32, 60):PACKAGE":"glass-sage"),"SETTINGS_APPEARANCE_NOT_APPLIED");frame(view,"document.querySelector('#settings-style-proof')?.remove();");busy=false;next.run();}catch(Exception e){fail(e);}}));
    }
    private static String sibling()throws Exception{
        byte[] html="<!doctype html><html><body style='background:white;color:black'><h2>独立默认外观</h2><input value='SIBLING_DRAFT'><button>默认 glass-sage</button></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8),settings="{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"BOTTOM_LEFT\",\"width\":300,\"height\":220,\"offsetX\":12,\"offsetY\":-12}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var refs=new LinkedHashMap<String,RuntimeResourceRef>();var blobs=new HashMap<String,byte[]>();for(var item:Map.of("ui/index.html",html,"ui/view-settings.json",settings).entrySet()){String hash=dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(item.getValue());blobs.put(hash,item.getValue());refs.put(item.getKey(),new RuntimeResourceRef(item.getKey(),hash,RuntimeResourceSide.CLIENT,item.getKey().endsWith("html")?"text/html":"application/json",item.getValue().length));}
        var h=refs.get("ui/index.html");var pkg=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,"外观隔离 · 本地视觉夹具","1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("ui",new RuntimeEntrypoint(h.path(),h.side(),h.sha256())),Map.of(),refs,PackageOrigin.LOCAL_STUDIO,false,1,h.sha256(),"LOCAL_VISUAL_FIXTURE_NOT_MODEL",0);
        return WebGuiHostAdapter.INSTANCE.openPackagePreview(pkg,blobs::get,h.path(),false);
    }
    public static void step(String view){if(finished)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{Files.createDirectories(root());require(!(paintOnly()&&Boolean.getBoolean("mineagent.viewSettingsSmoke")),"SETTINGS_INPUT_MODE_CONFLICT");require(ticks<1200,"SETTINGS_NATIVE_TIMEOUT");if(ticks%30==0)save("progress",Map.of("phase",phase,"ticks",ticks,"layout",host.viewLayout(view)==null?new JsonObject():host.viewLayout(view)));
            var layout=host.viewLayout(view);
            if(phase==0&&layout!=null&&layout.get("visible").getAsBoolean()&&paintMatches(view,layout,"initial")){verifyAnchor(layout);before=layout;save("initial",layout);style(view,true,()->{phase=1;after=ticks+15;});phase=-1;}
            if(phase==1&&ticks>=after&&!busy){busy=true;PackagePageAgent.captureManagedView(view).whenComplete((shot,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.write(root().resolve("custom-private.png"),shot.png());var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(shot.png()));int purple=0;for(int y=0;y<image.getHeight();y+=4)for(int x=0;x<image.getWidth();x+=4)if((image.getRGB(x,y)&0xffffff)==0x35203c)purple++;require(purple>100,"SETTINGS_PRIVATE_COLOR_MISSING");save("private-color",Map.of("purplePixelsSampled",purple,"width",image.getWidth(),"height",image.getHeight()));sibling=sibling();phase=2;after=ticks+25;busy=false;}catch(Exception e){fail(e);}}));}
            if(phase==2&&ticks>=after&&host.packageLoaded(sibling)&&!busy){style(sibling,false,()->{phase=21;after=ticks+15;});phase=-2;}
            if(phase==21&&ticks>=after&&!busy){busy=true;PackagePageAgent.captureManagedView(sibling).whenComplete((shot,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException("SETTINGS_SIBLING_NOT_RENDERED",error);require(shot.height()>=120,"SETTINGS_TITLEBAR_CONSUMED_BODY");Files.write(root().resolve("default-private.png"),shot.png());save("default-private-size",Map.of("width",shot.width(),"height",shot.height()));busy=false;phase=3;after=ticks+35;GLFW.glfwSetWindowSize(mc.getWindow().handle(),1400,900);}catch(Exception e){fail(e);}}));}
            if(phase==3&&ticks>=after&&layout.getAsJsonObject("viewport").get("width").getAsDouble()<before.getAsJsonObject("viewport").get("width").getAsDouble()&&paintMatches(view,layout,"resized")){
                verifyAnchor(layout);save("resized-anchor",layout);before=layout;
                if(paintOnly()){GLFW.glfwSetWindowSize(mc.getWindow().handle(),1600,1000);phase=32;after=ticks+60;}
                else{robot=new java.awt.Robot();GLFW.glfwFocusWindow(mc.getWindow().handle());cursor(before,0,0);phase=31;after=ticks+10;}
            }
            if(phase==32&&ticks>=after&&!busy&&layout.getAsJsonObject("viewport").get("width").getAsDouble()>before.getAsJsonObject("viewport").get("width").getAsDouble()&&paintMatches(view,layout,"restored")){
                require(paintOnly()&&robot==null&&!left,"SETTINGS_UNEXPECTED_SYSTEM_INPUT");verifyAnchor(layout);manual=layout;save("restored-anchor",layout);save("input-mode",Map.of("mode","NO_SYSTEM_INPUT","robotCreated",false,"manualOverrideVerified",false));busy=true;
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("custom-and-default.png"));mc.execute(()->{busy=false;phase=33;});}catch(Exception e){mc.execute(()->fail(e));}});
            }
            if(phase==33&&!busy){
                paintBaseline=JSON.toJsonTree(WebGuiPaintComposition.snapshot()).getAsJsonObject();require(paintBaseline.get("status").getAsString().equals("PAINT_LAYOUT_MATCHED"),"SETTINGS_PAINT_BASELINE_MISSING");
                var wrong=paintBaseline.getAsJsonObject("frame").deepCopy();wrong.addProperty("documentId",UUID.randomUUID().toString());wrong.addProperty("revision",wrong.get("revision").getAsLong()+1);
                main("(()=>{const reply=value=>window.mineagentQuery({request:JSON.stringify({channel:'worldUiSmokeProbe',paintLayoutSmoke:value}),persistent:false,onSuccess(){},onFailure(){}});window.mineagentPaintPlanQuery({request:"+JSON.toJson(wrong.toString())+",persistent:false,onSuccess(){reply('ACCEPTED')},onFailure(){reply('REJECTED')}});for(const n of document.querySelector('#mineagent-layout-sync').children)n.style.background='#000000';})();");
                phase=34;after=ticks+10;
            }
            if(phase==34&&ticks>=after&&invalidPlanReply!=null){
                require(invalidPlanReply.equals("REJECTED"),"SETTINGS_FOREIGN_PAINT_PLAN_ACCEPTED");var value=JSON.toJsonTree(WebGuiPaintComposition.snapshot()).getAsJsonObject();
                if(value.get("pairedUploads").getAsLong()>paintBaseline.get("pairedUploads").getAsLong()&&value.get("status").getAsString().equals("PAINT_LAYOUT_PENDING")){
                    save("unknown-token-paint",value);String token=paintBaseline.getAsJsonObject("frame").get("token").getAsString();
                    main("{const tag="+JSON.toJson(token)+";[...document.querySelector('#mineagent-layout-sync').children].forEach((n,i)=>n.style.background='#'+tag.slice(i*6,i*6+6));}");phase=35;after=ticks+10;
                }
            }
            if(phase==35&&ticks>=after&&paintMatches(view,layout,"after-unknown-token")){save("paint-negative-result",Map.of("foreignDocumentPlan","REJECTED","unknownPaintToken","UNMATCHED","restoredToken","MATCHED","systemInputInjected",false));phase=8;}
            if(phase==31&&ticks>=after){var b=before.getAsJsonObject("bounds");var v=before.getAsJsonObject("viewport");double x=(b.get("x").getAsDouble()+70)*mc.getWindow().getScreenWidth()/v.get("width").getAsDouble(),y=(b.get("y").getAsDouble()+20)*mc.getWindow().getScreenHeight()/v.get("height").getAsDouble();save("cursor-before-press",Map.of("expectedX",x,"expectedY",y,"actualX",mc.mouseHandler.xpos(),"actualY",mc.mouseHandler.ypos()));require(mc.isWindowActive()&&Math.abs(mc.mouseHandler.xpos()-x)<=2&&Math.abs(mc.mouseHandler.ypos()-y)<=2,"SETTINGS_CURSOR_NOT_SETTLED");robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);left=true;phase=4;after=ticks+10;}
            if(phase==4&&ticks>=after){cursor(before,-35,0);phase=5;after=ticks+15;}
            if(phase==5&&ticks>=after){robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);left=false;phase=6;after=ticks+25;}
            if(phase==6&&ticks>=after){save("manual-paint-diagnostic",Map.of("before",before,"current",layout,"paint",McefPaintBoundary.metrics(),"mouseX",mc.mouseHandler.xpos(),"mouseY",mc.mouseHandler.ypos(),"screenWidth",mc.getWindow().getScreenWidth(),"screenHeight",mc.getWindow().getScreenHeight(),"rendererWidth",host.browser().getRenderer().getTextureWidth(),"rendererHeight",host.browser().getRenderer().getTextureHeight()));require(layout.get("source").getAsString().equals("PLAYER")&&!sameBounds(layout,before),"SETTINGS_MANUAL_OVERRIDE_MISSING");manual=layout;save("manual",layout);GLFW.glfwSetWindowSize(mc.getWindow().handle(),1600,1000);phase=7;after=ticks+60;}
            if(phase==7&&ticks>=after){require(layout.get("source").getAsString().equals("PLAYER")&&sameBounds(layout,manual),"SETTINGS_DEFAULT_OVERWROTE_PLAYER");manual=layout;save("after-resize-player",layout);busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("custom-and-default.png"));busy=false;phase=8;}catch(Exception e){mc.execute(()->fail(e));}});}
            if(phase==8&&!busy){save("before-host-reopen",manual);finished=true;}
        }catch(Exception e){fail(e);}
    }
    public static boolean afterReopen(String view){if(reopened)return true;reopenTicks++;
        try{var value=WebGuiHostAdapter.INSTANCE.viewLayout(view);if(value==null||!value.get("visible").getAsBoolean()||!value.get("source").getAsString().equals(paintOnly()?"PACKAGE":"SAVED")||!paintMatches(view,value,"reopened")){require(reopenTicks<150,"SETTINGS_SAVED_LAYOUT_NOT_RESTORED");return false;}
            // PACKAGE coordinates are relative to the current available area, not
            // persisted absolute PLAYER coordinates. Dock text can legitimately
            // wrap differently in the new document; preserve exact anchor checks.
            boolean areaChanged=paintOnly()&&!sameAvailableArea(value,manual);
            if(areaChanged){
                save("reopen-area-changed",Map.of("expectedBefore",manual,"observedAfter",value,"ticks",reopenTicks));
                if(reopenTicks==30){
                    main("(()=>{const d=document.querySelector('#dock');window.mineagentQuery({request:JSON.stringify({channel:'worldUiSmokeProbe',settingsDock:{rect:d.getBoundingClientRect().toJSON(),status:document.querySelector('#status').textContent,children:[...d.children].map(n=>({text:n.textContent,hidden:n.hidden,display:getComputedStyle(n).display,rect:n.getBoundingClientRect().toJSON()}))}}),persistent:false,onSuccess(){},onFailure(){}});})();");
                    net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("reopen-current-area-game.png"));}catch(Exception e){Minecraft.getInstance().execute(()->fail(e));}});
                }
                if(reopenTicks<40)return false;
            }
            if(!areaChanged)require(sameBounds(value,manual),"SETTINGS_REOPEN_BOUNDS_CHANGED");if(paintOnly()){verifyAnchor(manual);verifyAnchor(value);}save("reopen-placement-parity",Map.of("areaChanged",areaChanged,"absoluteBoundsEqual",sameBounds(value,manual),"declaredAnchorVerified",paintOnly(),"playerSavedBoundsVerified",!paintOnly()));save("reopened",value);save("result",Map.of("status",paintOnly()?"PACKAGE_PAINT_RESIZE_REOPEN_VERIFIED":"PACKAGE_PLACEMENT_AND_APPEARANCE_NATIVE_VERIFIED","inputMode",paintOnly()?"NO_SYSTEM_INPUT":"OS_ROBOT","manualOverrideVerified",!paintOnly(),"paidCalls",0,"fullV1",false,"independentOpacityVerified",false));reopened=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(paintOnly()?"MINEAGENT_VIEW_SETTINGS_PAINT_OK":"MINEAGENT_VIEW_SETTINGS_OK");return true;
        }catch(Exception e){fail(e);return false;}
    }
    private static void fail(Exception e){if(left&&robot!=null){robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);left=false;}try{save("failure",Map.of("phase",phase,"error",e.toString()));}catch(Exception ignored){}WorldUiSmokeServer.failure="VIEW_SETTINGS_FAILED_"+phase;}
}
