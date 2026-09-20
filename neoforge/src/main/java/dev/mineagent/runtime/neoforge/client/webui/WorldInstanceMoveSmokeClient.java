package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.ui.WorldUiSmokeServer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;

/** Opt-in extension of the actual two-instance World UI fixture. No Provider or direct replacement of GUI writes. */
public final class WorldInstanceMoveSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks,phase;private static JsonObject probe,primary;
    private static boolean busy,captured,replayPending;public static boolean finished,durableDedup;
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldUiSmokeServer.directory());}
    private static void main(String code){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{"+code+"})();",host.browser().getURL(),0);}
    public static void accept(JsonObject value){probe=value;}
    public static void step(Session old)throws Exception{
        if(finished)return;var mc=Minecraft.getInstance();ticks++;if(ticks>2000)throw new IllegalStateException("INSTANCE_MOVE_GUI_TIMEOUT");String instance=WorldUiSmokeServer.first.toString();
        if(ticks%10==0)main("const s=document.querySelector('[data-world-move-status=\""+instance+"\"]');window.mineagentQuery({request:JSON.stringify({channel:'worldMoveProbe',status:s?.textContent||'',request:s?.dataset.worldMoveRequest?JSON.parse(s.dataset.worldMoveRequest):null,moveButton:!!document.querySelector('[data-world-move-open=\""+instance+"\"]'),fields:!!document.querySelector('[data-world-move-field=x]')}),persistent:false,onSuccess(){},onFailure(){}});");
        if(phase==0){WorldUiSmokeServer.moveBegin=true;main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();document.querySelector('#world-instances-open').click();");phase=1;}
        if(phase==1&&probe!=null&&probe.get("moveButton").getAsBoolean()){main("document.querySelector('[data-world-move-open=\""+instance+"\"]').click();");phase=2;probe=null;}
        if(phase==2&&probe!=null&&probe.get("fields").getAsBoolean()){submit(instance,518.5,81,.5);phase=3;}
        if(phase==3&&WorldUiSmokeServer.moveObserved&&probe!=null&&probe.get("status").getAsString().startsWith("APPLIED")){
            primary=probe.getAsJsonObject("request").deepCopy();Files.writeString(root().resolve("move-applied-gui.json"),JSON.toJson(probe));
            main("document.querySelector('[data-world-move-refresh=\""+instance+"\"]').click();");phase=4;probe=null;
        }
        if(phase==4&&probe!=null&&probe.get("fields").getAsBoolean()){submit(instance,524.5,80,.5);phase=5;}
        if(phase==5&&probe!=null&&probe.get("status").getAsString().equals("INSTANCE_MOVE_OCCUPIED")&&!busy){
            busy=true;Files.writeString(root().resolve("move-collision-gui.json"),JSON.toJson(probe));
            UiClientSessions.contentRequest("command",old,"worldui.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->mc.execute(()->{try{
                if(error!=null||receipt.code()!=Code.VIEW_NOT_RENDERED)throw new IllegalStateException("INSTANCE_MOVE_OLD_UI_STILL_AUTHORIZED",error);
                Files.writeString(root().resolve("move-old-session.json"),JSON.toJson(receipt));
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("move-gui.png"));captured=true;}catch(Exception e){mc.execute(()->fail(e));}});phase=6;
            }catch(Exception e){fail(e);}}));
        }
        if(phase==6&&captured){finished=true;Files.writeString(root().resolve("move-client.json"),JSON.toJson(Map.of("status","INSTANCE_TRANSLATION_NATIVE_GUI_VERIFIED","scope",instance,"request",primary,"collisionRejected",true,"oldContentSessionRevoked",true,"providerCalls",0,"fullV1",false)));}
    }
    private static void submit(String instance,double x,double y,double z){main("for(const [k,v]of Object.entries("+JSON.toJson(Map.of("x",x,"y",y,"z",z))+"))document.querySelector('[data-world-move-field='+k+']').value=v;const c=document.querySelector('[data-world-move-consent=\""+instance+"\"]');if(!c.checked)c.click();document.querySelector('[data-world-move-apply=\""+instance+"\"]').click();");}
    public static boolean replayAfterRootRenewal(){
        if(durableDedup)return true;if(replayPending||UiClientSessions.current()==null)return false;replayPending=true;
        var args=new LinkedHashMap<String,String>();for(String name:List.of("activationId","instanceId","canonical"))args.put(name,primary.get(name).getAsString());
        args.put("activationRevision",Long.toString(primary.get("activationRevision").getAsLong()));args.put("source",primary.get("source").toString());args.put("confirmed","true");for(String name:List.of("x","y","z"))args.put(name,Double.toString(primary.get(name).getAsDouble()));
        UiClientSessions.command("world.move",args,UUID.fromString(primary.get("operationId").getAsString())).whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{try{
            if(error!=null||receipt.code()!=Code.APPLIED)throw new IllegalStateException("INSTANCE_MOVE_DURABLE_REPLAY_FAILED",error);
            var move=JsonParser.parseString(receipt.values().get("move")).getAsJsonObject();if(!move.get("duplicate").getAsBoolean())throw new IllegalStateException("INSTANCE_MOVE_NOT_JOURNAL_REPLAY");
            Files.writeString(root().resolve("move-durable-replay.json"),JSON.toJson(receipt));durableDedup=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_MOVE_OK run={}",WorldUiSmokeServer.RUN);
        }catch(Exception e){fail(e);}}));return false;
    }
    private static void fail(Exception e){WorldUiSmokeServer.failure="INSTANCE_MOVE_SMOKE_FAILED";try{Files.writeString(root().resolve("move-failure.json"),JSON.toJson(Map.of("phase",phase,"error",e.toString(),"probe",probe==null?new JsonObject():probe)));}catch(Exception ignored){}throw new IllegalStateException(e);}
}
