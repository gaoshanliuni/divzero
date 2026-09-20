package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.ui.UiCapture;
import dev.mineagent.runtime.client.webui.NativePopupFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real CEF select popup paints, driven by Java Screen callbacks; never desktop input or a model response. */
public final class NativePopupSmokeClient {
    private static final Gson JSON=new Gson();private static int phase,ticks,after;private static boolean busy,done;
    private static String lower,upper;private static JsonObject observation,probe;private static double x,y;private static long openedEpoch,wheelPaints;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.nativePopupSmoke");}
    public static void accept(JsonObject value){if(value.has("nativePopupProbe"))probe=value.getAsJsonObject("nativePopupProbe");}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("native-popup-evidence");}
    private static void save(String file,Object data)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(file),JSON.toJson(data));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    private static void step(int n){phase=n;after=ticks+12;}
    @FunctionalInterface private interface Check<T>{void accept(T value)throws Exception;}
    private static <T> void await(CompletableFuture<T> work,int next,Check<T> check){busy=true;work.whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{if(error!=null)throw new IllegalStateException(error);check.accept(value);busy=false;step(next);}catch(Exception e){fail(e);}}));}
    private static String error(Throwable e){for(int i=0;i<8&&e.getCause()!=null;i++)e=e.getCause();return Objects.toString(e.getMessage(),"FAILED");}
    private static void observe(String id,int next){await(PackagePageAgent.inspectManagedView(id),next,text->{observation=JsonParser.parseString(text).getAsJsonObject();main("const n=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==="+JSON.toJson(id)+");const r=n.querySelector('iframe').getBoundingClientRect();window.mineagentQuery({request:JSON.stringify({channel:'worldUiSmokeProbe',nativePopupProbe:{id:"+JSON.toJson(id)+",frame:{x:r.x,y:r.y}}}),persistent:false,onSuccess(){},onFailure(){}});");});}
    private static JsonObject element(String id){for(var e:observation.getAsJsonArray("elements")){var n=e.getAsJsonObject();if(n.get("dataAiId").getAsString().equals(id))return n;}throw new IllegalStateException("POPUP_SELECT_NOT_OBSERVED");}
    private static void point(String id,String control){require(probe!=null&&probe.get("id").getAsString().equals(id),"POPUP_ROOT_PROBE");var map=WebGuiAtlasCompositor.inputMap();var s=map.frame().surfaces().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();var r=element(control).getAsJsonObject("bounds");var p=probe.getAsJsonObject("frame");toGui(s.destination().x()+p.get("x").getAsDouble()-s.source().x()+r.get("x").getAsDouble()+r.get("width").getAsDouble()/2,s.destination().y()+p.get("y").getAsDouble()-s.source().y()+r.get("y").getAsDouble()+r.get("height").getAsDouble()/2);}
    private static void toGui(double cx,double cy){var w=Minecraft.getInstance().getWindow();var f=WebGuiAtlasCompositor.inputMap().frame();x=cx*w.getGuiScaledWidth()/f.screenWidth();y=cy*w.getGuiScaledHeight()/f.screenHeight();}
    private static WebGuiInteractionScreen screen(){return (WebGuiInteractionScreen)Minecraft.getInstance().screen;}
    private static void press(){NativeAtlasInputSmokeClient.inject(()->screen().mouseClicked(new MouseButtonEvent(x,y,new MouseButtonInfo(0,0)),false));}
    private static void release(){NativeAtlasInputSmokeClient.inject(()->screen().mouseReleased(new MouseButtonEvent(x,y,new MouseButtonInfo(0,0))));}
    private static void key(int key){NativeAtlasInputSmokeClient.inject(()->{screen().keyPressed(new KeyEvent(key,GLFW.glfwGetKeyScancode(key),0));screen().keyReleased(new KeyEvent(key,GLFW.glfwGetKeyScancode(key),0));});}
    private static NativePopupFrame popup(){return (NativePopupFrame)WebGuiPopupCompositor.snapshot().get("frame");}
    private static boolean opened()throws Exception{var n=WebGuiPopupCompositor.snapshot();if(!n.get("error").toString().isEmpty()){save("popup-error.json",n);throw new IllegalStateException(n.get("error").toString());}return n.get("visible").equals(true)&&n.get("verified").equals(true)&&popup()!=null&&((Number)n.get("draws")).longValue()>0;}
    private static void screenshot(String name,int next){busy=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name));Minecraft.getInstance().execute(()->{busy=false;step(next);});}catch(Exception e){Minecraft.getInstance().execute(()->fail(e));}});}
    private static void rootProbe(){probe=null;main("const n=document.querySelector('[data-view-id=runtime-status]'),s=document.querySelector('#popup-root-select'),r=s.getBoundingClientRect();window.mineagentQuery({request:JSON.stringify({channel:'worldUiSmokeProbe',nativePopupProbe:{id:'runtime-status',control:{x:r.x,y:r.y,width:r.width,height:r.height},value:s.value,trusted:s.dataset.trusted||''}}),persistent:false,onSuccess(){},onFailure(){}});");}
    private static void rootPoint(){require(probe!=null&&probe.get("id").getAsString().equals("runtime-status"),"ROOT_POPUP_PROBE");var surface=WebGuiAtlasCompositor.inputMap().frame().surfaces().stream().filter(v->v.id().equals("runtime-status")).findFirst().orElseThrow();var r=probe.getAsJsonObject("control");toGui(surface.destination().x()+r.get("x").getAsDouble()-surface.source().x()+r.get("width").getAsDouble()/2,surface.destination().y()+r.get("y").getAsDouble()-surface.source().y()+r.get("height").getAsDouble()/2);}
    private static String page(String name,String color,int px,int py)throws Exception{
        StringBuilder options=new StringBuilder();for(int i=0;i<80;i++)options.append("<option value='v").append(i).append("'>Native option ").append(i).append("</option>");
        String html="<!doctype html><meta charset='utf-8'><body style='margin:0;padding:12px;background:"+color+";color:white;font:16px sans-serif'><h2>"+name+"</h2><label>Short menu <select data-ai-id='short' id='short' style='width:240px;font:16px sans-serif'>"+options.substring(0,options.indexOf("<option value='v6'"))+"</select></label><p>Long menu</p><select data-ai-id='long' id='long' style='width:240px;font:16px sans-serif'>"+options+"</select><p id='result'>short:v0 long:v0 changes:0 trusted:0</p><script>let changes=0,trusted=0;for(const s of document.querySelectorAll('select'))s.onchange=e=>{changes++;if(e.isTrusted)trusted++;document.querySelector('#result').textContent='short:'+document.querySelector('#short').value+' long:'+document.querySelector('#long').value+' changes:'+changes+' trusted:'+trusted;};</script></body>";
        String settings="{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"TOP_LEFT\",\"width\":420,\"height\":330,\"offsetX\":"+px+",\"offsetY\":"+py+",\"appearance\":\"PACKAGE\"}}}";
        var refs=new HashMap<String,RuntimeResourceRef>();var blobs=new HashMap<String,byte[]>();for(var e:Map.of("ui/index.html",html,"ui/view-settings.json",settings).entrySet()){var bytes=e.getValue().getBytes(StandardCharsets.UTF_8);String hash=UiCapture.sha256(bytes);blobs.put(hash,bytes);refs.put(e.getKey(),new RuntimeResourceRef(e.getKey(),hash,RuntimeResourceSide.CLIENT,e.getKey().endsWith("html")?"text/html":"application/json",bytes.length));}
        var e=refs.get("ui/index.html");var p=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,name,"1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("ui",new RuntimeEntrypoint(e.path(),e.side(),e.sha256())),Map.of(),refs,PackageOrigin.LOCAL_STUDIO,false,1,e.sha256(),"NATIVE_POPUP_LOCAL_FIXTURE_NOT_MODEL",0);return WebGuiHostAdapter.INSTANCE.openPackagePreview(p,blobs::get,e.path(),false);
    }
    public static void tick(boolean trusted)throws Exception{
        if(Boolean.getBoolean("mineagent.nativePopupLegacySmoke")){legacyTick(trusted);return;}
        if(done)return;ticks++;Files.createDirectories(root());if(!trusted||busy||ticks<after)return;var h=WebGuiHostAdapter.INSTANCE;
        try{require(ticks<2600,"POPUP_SMOKE_TIMEOUT_"+phase);
            switch(phase){
                case 0->{h.open();step(1);}
                case 1->{if(!h.ready()||UiClientSessions.current()==null)return;lower=page("Lower popup red","#d02020",80,20);upper=page("Upper popup blue","#2050d0",260,175);step(2);}
                case 2->{if(!h.packageLoaded(lower)||!h.packageLoaded(upper))return;h.emit("nativeAtlasStart",Map.of());step(3);}
                case 3->{try{WebGuiAtlasCompositor.inputMap();}catch(Exception pending){return;}h.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",1));h.emit("nativeAtlasOpacity",Map.of("viewId",lower,"opacity",1));step(4);}
                case 4->observe(upper,5);
                case 5->{save("before.json",observation);point(upper,"short");press();step(6);}
                case 6->{release();step(7);}
                case 7->{if(!opened())return;require(popup().owner().equals(upper),"POPUP_WRONG_OWNER");openedEpoch=popup().epoch();save("short-open.json",WebGuiPopupCompositor.snapshot());screenshot("short-open.png",8);}
                case 8->await(PackagePageAgent.captureManagedView(lower).handle((v,e)->e==null?"CAPTURED":error(e)),9,status->{require(status.equals("VIEW_POPUP_ACTIVE"),"POPUP_PRIVATE_CAPTURE_NOT_GATED_"+status);save("capture-blocked.json",Map.of("status",status));});
                case 9->{var r=popup().destination();toGui(r.x()+r.width()/2,r.y()+r.height()*2.5/6);press();step(10);}
                case 10->{release();step(11);}
                case 11->{if(WebGuiPopupCompositor.blockingCapture())return;observe(upper,12);}
                case 12->{save("short-selected.json",observation);require(observation.get("visibleText").getAsString().contains("short:v2 long:v0 changes:1 trusted:1"),"POPUP_OPTION_CLICK_FAILED");await(PackagePageAgent.captureManagedView(upper),13,shot->Files.write(root().resolve("private-after-short.png"),shot.png()));}
                case 13->{point(upper,"long");press();step(14);}
                case 14->{release();step(15);}
                case 15->{if(!opened())return;require(popup().epoch()!=openedEpoch&&popup().owner().equals(upper),"POPUP_EPOCH_REUSED");save("long-open.json",WebGuiPopupCompositor.snapshot());wheelPaints=((Number)WebGuiPopupCompositor.snapshot().get("paints")).longValue();var d=popup().destination();toGui(d.x()+d.width()/2,d.y()+d.height()/2);NativeAtlasInputSmokeClient.inject(()->screen().mouseScrolled(x,y,0,-4));step(40);}
                case 40->{if(!opened())return;require(((Number)WebGuiPopupCompositor.snapshot().get("paints")).longValue()>wheelPaints,"POPUP_WHEEL_DID_NOT_PAINT");save("long-after-wheel.json",WebGuiPopupCompositor.snapshot());key(GLFW.GLFW_KEY_END);step(16);}
                case 16->{if(!opened())return;save("long-after-end.json",WebGuiPopupCompositor.snapshot());screenshot("long-after-end.png",17);}
                case 17->{key(GLFW.GLFW_KEY_ENTER);step(18);}
                case 18->{if(WebGuiPopupCompositor.blockingCapture())return;observe(upper,19);}
                case 19->{save("long-selected.json",observation);require(observation.get("visibleText").getAsString().contains("short:v2 long:v79 changes:2 trusted:2"),"POPUP_KEYBOARD_SELECT_FAILED");point(upper,"short");press();step(20);}
                case 20->{release();step(21);}
                case 21->{if(!opened())return;toGui(10,650);press();step(22);}
                case 22->{release();step(23);}
                case 23->{if(WebGuiPopupCompositor.visible())return;require(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen,"POPUP_OUTSIDE_CLICK_PASSED_TO_WORLD");save("outside-dismiss.json",WebGuiPopupCompositor.snapshot());observe(upper,24);}
                case 24->{require(observation.get("visibleText").getAsString().contains("changes:2 trusted:2"),"POPUP_OUTSIDE_CHANGED_VALUE");point(upper,"long");press();step(25);}
                case 25->{release();step(26);}
                case 26->{if(!opened())return;h.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",0));step(27);}
                case 27->{if(WebGuiPopupCompositor.visible()||WebGuiPopupCompositor.blockingCapture())return;save("owner-zero-dismiss.json",WebGuiPopupCompositor.snapshot());observe(lower,28);}
                case 28->{save("sibling.json",observation);require(observation.get("visibleText").getAsString().contains("short:v0 long:v0 changes:0 trusted:0"),"POPUP_CHANGED_SIBLING");await(PackagePageAgent.captureManagedView(lower),29,shot->Files.write(root().resolve("sibling-private.png"),shot.png()));}
                case 29->{main("const n=document.querySelector('[data-view-id=runtime-status] .content'),s=document.createElement('select');s.id='popup-root-select';s.style.width='240px';s.innerHTML='<option value=0>Root option 0</option><option value=1>Root option 1</option><option value=2>Root option 2</option><option value=3>Root option 3</option>';s.onchange=e=>s.dataset.trusted=String(e.isTrusted);n.prepend(s);");step(30);}
                case 30->{var d=WebGuiAtlasCompositor.inputMap().frame().surfaces().stream().filter(v->v.id().equals("runtime-status")).findFirst().orElseThrow().destination();toGui(d.x()+16+20,d.y()+16+20);press();step(31);}
                case 31->{release();rootProbe();step(32);}
                case 32->{rootPoint();press();step(33);}
                case 33->{release();step(34);}
                case 34->{if(!opened())return;require(popup().owner().equals("runtime-status"),"ROOT_POPUP_OWNER");save("root-open.json",WebGuiPopupCompositor.snapshot());screenshot("root-open.png",35);}
                case 35->{var d=popup().destination();toGui(d.x()+d.width()/2,d.y()+d.height()*1.5/4);press();step(36);}
                case 36->{release();step(37);}
                case 37->{if(WebGuiPopupCompositor.blockingCapture())return;rootProbe();step(38);}
                case 38->{require(probe.get("value").getAsString().equals("1")&&probe.get("trusted").getAsString().equals("true"),"ROOT_POPUP_SELECTION");save("root-selected.json",probe);rootPoint();press();step(39);}
                case 39->{release();step(41);}
                case 41->{if(!opened())return;save("host-close-before.json",WebGuiPopupCompositor.snapshot());save("base-before-close.json",WebGuiPaintComposition.snapshot());h.close();step(42);}
                case 42->{var state=WebGuiPopupCompositor.snapshot();require(!WebGuiPopupCompositor.visible()&&!WebGuiPopupCompositor.blockingCapture()&&state.get("rendererPresent").equals(false)&&((Number)state.get("pendingBytes")).intValue()==0&&((Number)state.get("pendingQueries")).intValue()==0&&McefTextureRetirement.pending()==0,"POPUP_RESOURCES_NOT_RETIRED");save("result.json",Map.of("status","NATIVE_SELECT_POPUP_COMPOSITION_AND_INPUT_VERIFIED","upper",upper,"lower",lower,"modelCalls",0,"systemInputInjected",false,"fullV1",false,"rootSelectVerified",true,"popup",state,"retirementPending",McefTextureRetirement.pending()));done=true;Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_NATIVE_POPUP_OK javaCallbacks=true");dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_OK nativePopupEnvironmentOnly=true");}
                default->throw new IllegalStateException("POPUP_SMOKE_PHASE");
            }
        }catch(Exception e){fail(e);}
    }
    private static void legacyPoint(String control){var r=element(control).getAsJsonObject("bounds");var p=probe.getAsJsonObject("frame");var m=WebGuiPaintComposition.matched().orElseThrow();var w=Minecraft.getInstance().getWindow();x=(p.get("x").getAsDouble()+r.get("x").getAsDouble()+r.get("width").getAsDouble()/2)*w.getGuiScaledWidth()/m.frame().width();y=(p.get("y").getAsDouble()+r.get("y").getAsDouble()+r.get("height").getAsDouble()/2)*w.getGuiScaledHeight()/m.frame().height();}
    private static void legacyTick(boolean trusted)throws Exception{
        if(done)return;ticks++;Files.createDirectories(root());if(!trusted||busy||ticks<after)return;var h=WebGuiHostAdapter.INSTANCE;
        try{require(ticks<1800,"LEGACY_POPUP_TIMEOUT_"+phase);switch(phase){
            case 0->{h.open();step(1);}
            case 1->{if(!h.ready()||UiClientSessions.current()==null)return;upper=page("Legacy popup blue","#2050d0",160,30);step(2);}
            case 2->{if(!h.packageLoaded(upper))return;observe(upper,3);}
            case 3->{legacyPoint("short");press();step(4);}
            case 4->{release();step(5);}
            case 5->{if(!WebGuiPopupCompositor.visible())return;require(!WebGuiAtlasCompositor.active(),"LEGACY_UNEXPECTED_ATLAS");save("legacy-open.json",WebGuiPopupCompositor.snapshot());screenshot("legacy-open.png",6);}
            case 6->await(PackagePageAgent.captureManagedView(upper).handle((v,e)->e==null?"CAPTURED":error(e)),7,status->{require(status.equals("VIEW_POPUP_ACTIVE"),"LEGACY_POPUP_CAPTURE_NOT_GATED");save("capture-blocked.json",Map.of("status",status));});
            case 7->{var r=(java.awt.Rectangle)WebGuiPopupCompositor.snapshot().get("nativeRect");var w=Minecraft.getInstance().getWindow();x=(r.x+r.width/2d)*w.getGuiScaledWidth()/w.getWidth();y=(r.y+r.height*2.5/6)*w.getGuiScaledHeight()/w.getHeight();press();step(8);}
            case 8->{release();step(9);}
            case 9->{if(WebGuiPopupCompositor.blockingCapture())return;observe(upper,10);}
            case 10->{require(observation.get("visibleText").getAsString().contains("short:v2 long:v0 changes:1 trusted:1"),"LEGACY_POPUP_SELECTION_FAILED");save("legacy-selected.json",observation);await(PackagePageAgent.captureManagedView(upper),11,shot->Files.write(root().resolve("legacy-private.png"),shot.png()));}
            case 11->{save("result.json",Map.of("status","LEGACY_NATIVE_POPUP_REGRESSION_VERIFIED","popup",WebGuiPopupCompositor.snapshot(),"modelCalls",0,"systemInputInjected",false,"fullV1",false));done=true;h.close();Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_NATIVE_POPUP_OK legacy=true javaCallbacks=true");dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_OK nativePopupEnvironmentOnly=true");}
            default->throw new IllegalStateException("LEGACY_POPUP_PHASE");
        }}catch(Exception e){fail(e);}
    }
    private static void fail(Exception e){if(done)return;done=true;try{save("failure.json",Map.of("phase",phase,"error",e.toString(),"popup",WebGuiPopupCompositor.snapshot(),"base",WebGuiPaintComposition.snapshot()));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("NATIVE_POPUP_SMOKE_FAILED",e);}
}
