package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.ui.UiCapture;
import dev.mineagent.runtime.agent.ui.UiAgentController;
import dev.mineagent.runtime.client.webui.NativeAtlasFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Explicit Java Screen-callback + local Page Port fixture. Never OS input, a model call or a business task. */
public final class NativeAtlasInputSmokeClient {
    private static final Gson JSON=new Gson();
    private static int phase,ticks,after;private static boolean done,busy,injecting;
    private static String lower,upper,upperDocument;private static JsonObject observation,probe;private static double px,py;
    private static NativeAtlasFrame.Rect beforeDrag;private static UiAgentController.Port port;private static UiCapture.Image capture;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.nativeAtlasInputSmoke");}
    static boolean blocksExternalCallbacks(){return enabled()&&!injecting;}
    public static void accept(JsonObject n){if(n.has("atlasInputProbe"))probe=n.getAsJsonObject("atlasInputProbe");}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("native-atlas-input-evidence");}
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(value));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    @FunctionalInterface private interface Checked<T>{void accept(T value)throws Exception;}
    private static <T> void await(CompletableFuture<T> future,int next,Checked<T> use){busy=true;future.whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{if(error!=null)throw new IllegalStateException(error);use.accept(value);phase=next;after=ticks+15;busy=false;}catch(Exception failed){fail(failed);}}));}
    static void inject(Runnable work){injecting=true;try{work.run();}finally{injecting=false;}}
    private static WebGuiInteractionScreen screen(){return (WebGuiInteractionScreen)Minecraft.getInstance().screen;}
    private static NativeAtlasFrame frame(){return WebGuiAtlasCompositor.inputMap().frame();}
    private static NativeAtlasFrame.Surface surface(String id){return frame().surfaces().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static void probe(String id){probe=null;main("const n=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==="+JSON.toJson(id)+");const f=n.querySelector('iframe').getBoundingClientRect();window.mineagentQuery({request:JSON.stringify({channel:'worldUiSmokeProbe',atlasInputProbe:{id:"+JSON.toJson(id)+",frame:{x:f.x,y:f.y,width:f.width,height:f.height}}}),persistent:false,onSuccess(){},onFailure(){}});");}
    private static void observe(String id,int next){await(PackagePageAgent.inspectManagedView(id),next,text->{observation=JsonParser.parseString(text).getAsJsonObject();probe(id);});}
    private static JsonObject element(String name){for(var e:observation.getAsJsonArray("elements")){var n=e.getAsJsonObject();if(n.get("dataAiId").getAsString().equals(name))return n;}throw new IllegalStateException("FIXTURE_ELEMENT_MISSING_"+name);}
    private static void point(String id,String name){require(probe!=null&&probe.get("id").getAsString().equals(id),"FIXTURE_PROBE_MISSING");var b=element(name).getAsJsonObject("bounds");var f=probe.getAsJsonObject("frame");var s=surface(id);double x=s.destination().x()+f.get("x").getAsDouble()-s.source().x()+b.get("x").getAsDouble()+b.get("width").getAsDouble()/2;
        double y=s.destination().y()+f.get("y").getAsDouble()-s.source().y()+b.get("y").getAsDouble()+Math.min(14,b.get("height").getAsDouble()/2);var w=Minecraft.getInstance().getWindow();px=x*w.getGuiScaledWidth()/frame().screenWidth();py=y*w.getGuiScaledHeight()/frame().screenHeight();}
    private static void press(){inject(()->screen().mouseClicked(new MouseButtonEvent(px,py,new MouseButtonInfo(0,0)),false));}
    private static void release(){inject(()->screen().mouseReleased(new MouseButtonEvent(px,py,new MouseButtonInfo(0,0))));}
    private static void step(int next){phase=next;after=ticks+15;}
    private static int count(String key){var m=java.util.regex.Pattern.compile(key+":(\\d+)").matcher(observation.get("visibleText").getAsString());require(m.find(),"FIXTURE_COUNTER_MISSING_"+key);return Integer.parseInt(m.group(1));}
    private static String page(String title,String color)throws Exception{
        String html="""
            <!doctype html><html><meta charset='utf-8'><body style='margin:0;padding:12px;background:COLOR;color:white;font:16px sans-serif'>
            <button data-ai-id='tap' style='width:140px;height:30px'>Tap</button><input data-ai-id='draft' value='seed' style='width:180px;height:24px'>
            <p id='result'>clicks:0 trusted:0 keys:0 wheels:0 scroll:0 canvas:0</p>
            <canvas data-ai-id='canvas' width='120' height='50' style='display:block;background:#37c58d'></canvas>
            <div data-ai-id='scroll' data-ai-coordinate-target style='height:80px;overflow:auto;border:1px solid white;margin-top:8px'><div style='height:500px'>Scrollable local page</div></div>
            <pre id='keylog' style='font-size:10px;white-space:pre-wrap'></pre><script>let clicks=0,trusted=0,keys=0,wheels=0,canvas=0;const box=document.querySelector('[data-ai-id=scroll]');function show(){document.querySelector('#result').textContent=`clicks:${clicks} trusted:${trusted} keys:${keys} wheels:${wheels} scroll:${Math.round(box.scrollTop)} canvas:${canvas}`;}
            document.querySelector('button').onclick=e=>{clicks++;if(e.isTrusted)trusted++;show()};document.addEventListener('keydown',e=>{keys++;document.querySelector('#keylog').textContent+=JSON.stringify({key:e.key,code:e.code,ctrl:e.ctrlKey})+';';show()});box.addEventListener('wheel',()=>{wheels++;show()});box.onscroll=show;document.querySelector('canvas').onclick=()=>{canvas++;show()};</script></body></html>
            """.replace("COLOR",color);
        String settings="{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"TOP_LEFT\",\"width\":440,\"height\":380,\"offsetX\":140,\"offsetY\":40,\"appearance\":\"PACKAGE\"}}}";
        var refs=new LinkedHashMap<String,RuntimeResourceRef>();var bytesByHash=new HashMap<String,byte[]>();for(var e:Map.of("ui/index.html",html,"ui/view-settings.json",settings).entrySet()){byte[] bytes=e.getValue().getBytes(StandardCharsets.UTF_8);String hash=UiCapture.sha256(bytes);refs.put(e.getKey(),new RuntimeResourceRef(e.getKey(),hash,RuntimeResourceSide.CLIENT,e.getKey().endsWith("html")?"text/html":"application/json",bytes.length));bytesByHash.put(hash,bytes);}
        var e=refs.get("ui/index.html");var p=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,title,"1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("ui",new RuntimeEntrypoint(e.path(),e.side(),e.sha256())),Map.of(),refs,PackageOrigin.LOCAL_STUDIO,false,1,e.sha256(),"LOCAL_CALLBACK_FIXTURE_NOT_MODEL",0);
        return WebGuiHostAdapter.INSTANCE.openPackagePreview(p,bytesByHash::get,e.path(),false);
    }
    private static JsonObject action(String kind,String name){var a=new JsonObject();a.addProperty("action",kind);a.addProperty("operationId",UUID.randomUUID().toString());if(name!=null)a.add("elementRef",element(name).get("elementRef"));return a;}
    private static JsonObject coordinate(){var b=element("canvas").getAsJsonObject("bounds");var m=capture.manifest();var a=action("clickAt",null);a.addProperty("captureId",m.captureId().toString());a.addProperty("x",(b.get("x").getAsDouble()+30-m.frameX())*m.scaleX());a.addProperty("y",(b.get("y").getAsDouble()+20-m.frameY())*m.scaleY());return a;}
    private static void snapshot(String name,int next){busy=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name));Minecraft.getInstance().execute(()->{busy=false;step(next);});}catch(Exception e){Minecraft.getInstance().execute(()->fail(e));}});}
    public static void tick(boolean trusted)throws Exception{
        if(done)return;ticks++;Files.createDirectories(root());if(!trusted||busy||ticks<after)return;var host=WebGuiHostAdapter.INSTANCE;
        try{require(ticks<3000,"ATLAS_INPUT_TIMEOUT_"+phase);
            switch(phase){
                case 0->{host.open();step(1);}
                case 1->{if(host.ready()&&UiClientSessions.current()!=null){lower=page("Lower input red","#d02020");upper=page("Upper input blue","#2050d0");step(2);}}
                case 2->{if(host.packageLoaded(lower)&&host.packageLoaded(upper)){observe(upper,3);}}
                case 3->{upperDocument=observation.get("documentId").getAsString();write("before-atlas.json",observation);host.emit("nativeAtlasStart",Map.of());step(4);}
                case 4->{if(WebGuiAtlasCompositor.inputAvailable()){try{frame();}catch(IllegalStateException pending){return;}host.emit("nativeAtlasOpacity",Map.of("viewId",lower,"opacity",1));host.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",1));step(5);}}
                case 5->{boolean denied=false;try{port=PackagePageAgent.controlPreview(lower);}catch(IllegalStateException e){denied="VIEW_OCCLUDED".equals(e.getMessage());}require(denied,"ATLAS_COVERED_AGENT_ALLOWED");write("covered-agent-denied.json",Map.of("result","VIEW_OCCLUDED","acquisitionRejected",true));await(PackagePageAgent.captureManagedView(lower),40,image->{Files.write(root().resolve("covered-lower-private.png"),image.png());});}
                case 40->observe(upper,6);
                case 6->{point(upper,"tap");inject(()->screen().mouseMoved(px,py));step(7);}
                case 7->{press();step(8);}
                case 8->{release();step(9);}
                case 9->observe(upper,10);
                case 10->{write("player-click.json",observation);require(count("clicks")==1&&count("trusted")==1,"ATLAS_PLAYER_CLICK_FAILED");point(upper,"draft");press();step(11);}
                case 11->{release();step(12);}
                case 12->{inject(()->{var s=screen();s.keyPressed(new KeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL,GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_LEFT_CONTROL),GLFW.GLFW_MOD_CONTROL));s.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A,GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_A),GLFW.GLFW_MOD_CONTROL));s.keyReleased(new KeyEvent(GLFW.GLFW_KEY_A,GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_A),GLFW.GLFW_MOD_CONTROL));s.keyReleased(new KeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL,GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_LEFT_CONTROL),0));for(int c:"PLAYER_TYPED".codePoints().toArray())s.charTyped(new CharacterEvent(c));});step(13);}
                case 13->observe(upper,14);
                case 14->{write("player-typed.json",observation);require(element("draft").get("value").getAsString().equals("PLAYER_TYPED")&&count("keys")>=2,"ATLAS_KEYBOARD_FAILED");point(upper,"scroll");inject(()->screen().mouseScrolled(px,py,0,-4));step(15);}
                case 15->observe(upper,16);
                case 16->{write("player-wheel.json",observation);require(count("wheels")>0&&count("scroll")>0,"ATLAS_WHEEL_FAILED");beforeDrag=surface(upper).destination();var w=Minecraft.getInstance().getWindow();px=(beforeDrag.x()+16+65)*w.getGuiScaledWidth()/frame().screenWidth();py=(beforeDrag.y()+16+20)*w.getGuiScaledHeight()/frame().screenHeight();press();step(17);}
                case 17->{var w=Minecraft.getInstance().getWindow();px+=30.0*w.getGuiScaledWidth()/frame().screenWidth();py+=15.0*w.getGuiScaledHeight()/frame().screenHeight();inject(()->screen().mouseMoved(px,py));step(18);}
                case 18->{release();step(19);}
                case 19->{var moved=surface(upper).destination();write("root-drag.json",Map.of("before",beforeDrag,"after",moved,"native",WebGuiAtlasCompositor.snapshot()));require(Math.abs(moved.x()-beforeDrag.x()-30)<2&&Math.abs(moved.y()-beforeDrag.y()-15)<2,"ATLAS_ROOT_DRAG_FAILED");port=PackagePageAgent.controlPreview(upper);await(port.inspect(),20,t->observation=JsonParser.parseString(t).getAsJsonObject());}
                case 20->{var a=action("fill","draft");a.addProperty("value","PAGE_PORT_TYPED");await(port.act(a.toString()),21,t->{write("page-port-fill.json",JsonParser.parseString(t));require(t.contains("APPLIED_DOM"),"ATLAS_PORT_FILL_FAILED");});}
                case 21->await(port.capture(),22,image->{capture=image;Files.write(root().resolve("agent-private.png"),image.png().bytes());write("capture-manifest.json",image.manifest());});
                case 22->await(port.inspect(),23,t->observation=JsonParser.parseString(t).getAsJsonObject());
                case 23->await(port.act(coordinate().toString()),24,t->{write("page-port-coordinate.json",JsonParser.parseString(t));require(t.contains("APPLIED_DOM")&&t.contains("targetPixelsVerified\":true"),"ATLAS_COORDINATE_FAILED");});
                case 24->await(port.capture(),25,image->{capture=image;host.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",.5));});
                case 25->{var before=coordinate();await(port.act(before.toString()).handle((value,error)->error==null?value:rootMessage(error)),26,t->{write("stale-capture.json",Map.of("result",t));require(t.contains("STALE_LAYOUT")||t.contains("STALE_CAPTURE"),"ATLAS_STALE_CAPTURE_ACCEPTED");port.cancel();port=null;});}
                case 26->{host.emit("nativeAtlasOpacity",Map.of("viewId",upper,"opacity",0));step(27);}
                case 27->{boolean denied=false;try{port=PackagePageAgent.controlPreview(upper);}catch(IllegalStateException e){denied="VIEW_NOT_RENDERED".equals(e.getMessage());}require(denied,"ATLAS_ZERO_AGENT_ALLOWED");write("zero-agent-denied.json",Map.of("result","VIEW_NOT_RENDERED","acquisitionRejected",true));step(28);}
                case 28->await(PackagePageAgent.captureManagedView(upper),29,image->{Files.write(root().resolve("zero-private.png"),image.png());});
                case 29->observe(lower,30);
                case 30->{require(count("clicks")==0,"ATLAS_WRONG_SIBLING_CHANGED");point(lower,"tap");var w=Minecraft.getInstance().getWindow();var route=WebGuiAtlasCompositor.route(px*w.getWidth()/w.getGuiScaledWidth(),py*w.getHeight()/w.getGuiScaledHeight(),null).orElseThrow();require(route.point().viewId().equals(lower),"ATLAS_ZERO_HIT_NOT_LOWER");press();step(31);}
                case 31->{release();step(32);}
                case 32->observe(lower,33);
                case 33->{write("zero-lower-click.json",observation);require(count("clicks")==1&&count("trusted")==1,"ATLAS_LOWER_CLICK_FAILED");observe(upper,34);}
                case 34->{write("final-upper.json",observation);require(observation.get("documentId").getAsString().equals(upperDocument)&&count("clicks")==1&&count("canvas")==1&&element("draft").get("value").getAsString().equals("PAGE_PORT_TYPED"),"ATLAS_PAGE_IDENTITY_OR_STATE_FAILED");snapshot("after-input.png",35);}
                case 35->{var w=Minecraft.getInstance().getWindow();require(WebGuiAtlasCompositor.worldAt(w.getWidth()-30,w.getHeight()-30),"ATLAS_BLANK_CHROME_SWALLOWS_WORLD");write("result.json",Map.of("status","ATLAS_CALLBACK_AND_PAGE_PORT_VERIFIED","systemInputInjected",false,"modelCalls",0,"fullV1",false,"worldFallbackNativeVerified",false,"osInputVerified",false,"upper",upper,"lower",lower,"native",WebGuiAtlasCompositor.snapshot()));done=true;host.close();Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_NATIVE_ATLAS_INPUT_OK javaCallbacks=true systemInput=false");dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_OK nativeAtlasEnvironmentOnly=true");}
                default->throw new IllegalStateException("ATLAS_INPUT_PHASE");
            }
        }catch(Exception e){fail(e);}
    }
    private static String rootMessage(Throwable error){for(int i=0;i<8&&error.getCause()!=null;i++)error=error.getCause();return Objects.toString(error.getMessage(),error.getClass().getSimpleName());}
    private static void fail(Exception e){if(done)return;done=true;try{write("failure.json",Map.of("phase",phase,"error",e.toString(),"native",WebGuiAtlasCompositor.snapshot()));}catch(Exception ignored){}if(port!=null)port.cancel();WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("NATIVE_ATLAS_INPUT_FAILED",e);}
}
