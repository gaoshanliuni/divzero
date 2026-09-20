package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.HudRestoreEntry;
import dev.mineagent.runtime.neoforge.ui.HudRestoreSmokeServer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;
/** Real game/CEF reconnect acceptance. Does not open the host to make restoration appear to work. */
final class HudRestoreSmokeClient {
    private static final Gson JSON=new Gson();
    private static int ticks,phase,next;private static boolean ended,busy;private static String view;
    private static HudRestoreEntry saved;private static Session previous;
    static void tick()throws Exception{
        if(ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{
            if(mc.player==null||!HudRestoreSmokeServer.ready){if(ticks>1800)throw new IllegalStateException("RESTORE_WORLD_TIMEOUT");return;}
            Files.createDirectories(root());
            if(saved==null){var checkpoint=JsonParser.parseString(Files.readString(mc.gameDirectory.toPath().resolve("hud-restore-checkpoint.json"))).getAsJsonObject();saved=JSON.fromJson(checkpoint.get("entry"),HudRestoreEntry.class);previous=JSON.fromJson(checkpoint.get("session"),Session.class);next=ticks+120;}
            if("rejected".equals(System.getProperty("mineagent.hudPersistencePhase"))){
                if(ticks<next)return;
                if(!HudPersistenceClient.status(saved.key()).equals("RESTORE_FAILED")){if(ticks>1800)throw new IllegalStateException("HUD_REJECTION_TIMEOUT");return;}
                if(!HudPersistenceClient.mountedViews().isEmpty()||mc.screen!=null)throw new IllegalStateException("UNMATCHED_HUD_RENDERED_OR_STOLE_FOCUS");
                Files.writeString(root().resolve("rejected.json"),JSON.toJson(Map.of("mountedViews",HudPersistenceClient.mountedViews(),"status",HudPersistenceClient.status(saved.key()),"saved",HudPersistenceClient.entries(),"screenAbsent",true,"providerCalls",0)));finish();return;
            }
            if("closed".equals(System.getProperty("mineagent.hudPersistencePhase"))){
                if(ticks<next)return;
                if(host.browser()!=null||!HudPersistenceClient.entries().isEmpty())throw new IllegalStateException("CLOSED_HUD_RESURRECTED");
                Files.writeString(root().resolve("closed-verified.json"),JSON.toJson(Map.of("browserAbsent",true,"bookmarksEmpty",true,"providerCalls",0,"fullV1",false)));finish();return;
            }
            if(phase==0){
                view=HudPersistenceClient.mountedViews().stream().filter(id->{var s=PackageContentClient.session(id);return s!=null&&s.binding().targetObjectId().equals(saved.targetId().toString());}).findFirst().orElse(null);
                if(view!=null){var fresh=PackageContentClient.session(view);saved.require(fresh,previous.binding().worldId(),previous.binding().viewerPlayerId(),saved.canonicalSha256(),saved.entryPath());
                    if(fresh.sessionId().equals(previous.sessionId())||fresh.serverInstanceId().equals(previous.serverInstanceId())||mc.screen!=null)throw new IllegalStateException("HUD_RESTORE_REUSED_AUTHORITY_OR_STOLE_FOCUS");
                    Files.writeString(root().resolve("fresh-session.json"),JSON.toJson(Map.of("old",previous,"fresh",fresh,"screenAbsent",true,"inputDriver","NO_HOST_OPEN_NO_OS_INPUT")));phase=1;
                }
            }
            if(phase==1&&!busy)inspect("restored-dom.json","19",()->{HudRestoreSmokeServer.externalRequested=true;phase=2;});
            if(phase==2&&!busy&&HudRestoreSmokeServer.externalChanged)inspect("updated-dom.json","27",()->{phase=3;next=ticks+20;});
            if(phase==3&&ticks>=next&&!busy){
                if(mc.screen!=null)throw new IllegalStateException("HUD_RESTORE_STOLE_GAME_INPUT");busy=true;
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("restored-gameplay.png"));mc.execute(()->{busy=false;phase=4;});}catch(Exception e){mc.execute(()->fail(e));}});
            }
            if(phase==4){
                host.open();host.browser().executeJavaScript("document.querySelector('[data-view-id=\""+view+"\"] [aria-label=关闭]').click();",host.browser().getURL(),0);phase=5;next=ticks+30;
            }
            if(phase==5&&ticks>=next&&HudPersistenceClient.settled()&&HudPersistenceClient.entries().isEmpty()&&!PackageContentClient.owns(view)){
                Files.writeString(root().resolve("result.json"),JSON.toJson(Map.of("status","HUD_RESTART_RESTORE_VERIFIED","providerCalls",0,"closedPreferenceRemoved",true,"fullV1",false)));finish();
            }
            if(ticks>2400)throw new IllegalStateException("HUD_RESTORE_TIMEOUT phase="+phase);
        }catch(Exception e){fail(e);}
    }
    private static void inspect(String file,String score,Runnable next){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{
        try{busy=false;if(error!=null)throw new IllegalStateException(error);String text=JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString();if(!scoreVisible(text,"Alice",Integer.parseInt(score))||!scoreVisible(text,"Bob",2))return;
            Files.writeString(root().resolve(file),value);next.run();}catch(Exception e){fail(e);}
    }));}
    static boolean scoreVisible(String text,String holder,int score){return java.util.regex.Pattern.compile("(?m)^持有者："+java.util.regex.Pattern.quote(holder)+"\\n"+score+"$").matcher(text.replace("\r\n","\n")).find();}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("hud-restore-evidence").resolve(HudRestoreSmokeServer.RUN);}
    private static void finish(){ended=true;WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_HUD_RESTORE_OK run={}",HudRestoreSmokeServer.RUN);}
    private static void fail(Exception e){ended=true;try{Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",e.toString())));}catch(Exception write){e.addSuppressed(write);}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("HUD_RESTORE_FAILED",e);}
}
