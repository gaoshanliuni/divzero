package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.ui.PersonaSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
/** Real form controls plus authenticated negative protocol tests; no model response is fabricated. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PersonaSmokeClient {
    private static final Gson JSON=new Gson();private static int phase,ticks,after;private static boolean backup,trusted,trustPressed,busy,captured;private static JsonObject probe;private static final UUID collaboratorOperation=UUID.randomUUID();
    private static final String TEMP_DRAFT="切换 AI 时保留的未保存草稿";
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("persona-evidence");}
    public static void accept(JsonObject v){probe=v;}
    private static void js(String script){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{"+script+"})();",host.browser().getURL(),0);}
    private static void choose(UUID id){probe=null;js("const e=document.querySelector('#persona-agent');if(e){e.value="+JSON.toJson(id.toString())+";e.dispatchEvent(new Event('change',{bubbles:true}));}");}
    private static void edit(String value,boolean save){js("const e=document.querySelector('#persona-text');if(e){e.value="+JSON.toJson(value)+";e.dispatchEvent(new Event('input',{bubbles:true}));"+(save?"document.querySelector('#persona-save').click();":"")+"}");}
    private static boolean loaded(UUID id,String value){return probe!=null&&probe.has("text")&&probe.get("agent").getAsString().equals(id.toString())&&probe.get("text").getAsString().equals(value);}
    private static boolean revision(long value){return probe!=null&&probe.get("revision").getAsString().equals(Long.toString(value));}
    private static void fail(Throwable e){PersonaSmokeServer.failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static void store(String name,Object value){try{Files.writeString(root().resolve(PersonaSmokeServer.stage()+"-"+name+".json"),JSON.toJson(value));}catch(Exception e){fail(e);}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!PersonaSmokeServer.enabled()||phase==99)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Files.createDirectories(root());
        if(phase==98){if(ticks>=after){phase=99;mc.stop();}return;}
        if(PersonaSmokeServer.failure!=null||ticks>3500){store("failure",Map.of("phase",phase,"error",String.valueOf(PersonaSmokeServer.failure),"probe",probe==null?new JsonObject():probe));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Persona fixture failed"));phase=98;after=ticks+20;return;}
        if(phase==90){if(ticks>=after){phase=99;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PERSONA_{}_OK",PersonaSmokeServer.stage().toUpperCase(java.util.Locale.ROOT));mc.stop();}return;}
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!trusted){
            if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!trustPressed)for(var c:screen.children())if(c instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().equals("信任此服务器")){b.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});trustPressed=true;break;}
            String fp=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");if(!fp.isEmpty()&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fp)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;mc.setScreen(null);}else return;
        }
        if(phase==0&&trusted&&PersonaSmokeServer.ready){host.open();phase=1;}
        if(!host.ready()||UiClientSessions.current()==null)return;
        if(ticks%10==0)js("const t=document.querySelector('#persona-text'),s=document.querySelector('#persona-agent');window.mineagentQuery({request:JSON.stringify({channel:'personaProbe',agent:s?.value||'',...(t?{text:t.value}:{}),revision:document.querySelector('#persona-fields')?.dataset.personaRevision||'',status:document.querySelector('#persona-status')?.textContent||''}),persistent:false,onSuccess(){},onFailure(){}});");
        if(ticks%40==0)store("progress",Map.of("phase",phase,"ticks",ticks,"probe",probe==null?new JsonObject():probe));
        if(phase==1){js("document.querySelector('#open-persona').click();");phase=2;after=ticks+30;}
        if(phase==2&&ticks>=after){choose(PersonaSmokeServer.a);phase=PersonaSmokeServer.stage().equals("prepare")?3:30;}
        if(phase==3&&loaded(PersonaSmokeServer.a,"")){edit(PersonaSmokeServer.A_TEXT,true);phase=4;}
        if(phase==4&&revision(1)&&loaded(PersonaSmokeServer.a,PersonaSmokeServer.A_TEXT)){edit(TEMP_DRAFT,false);choose(PersonaSmokeServer.b);phase=5;}
        if(phase==5&&loaded(PersonaSmokeServer.b,"")){edit(PersonaSmokeServer.B_TEXT,true);phase=6;}
        if(phase==6&&revision(1)&&loaded(PersonaSmokeServer.b,PersonaSmokeServer.B_TEXT)){choose(PersonaSmokeServer.a);phase=7;}
        if(phase==7&&loaded(PersonaSmokeServer.a,TEMP_DRAFT)){store("switched-draft",probe);js("document.querySelector('#persona-refresh').click();");phase=8;}
        if(phase==8&&loaded(PersonaSmokeServer.a,PersonaSmokeServer.A_TEXT)){js("document.querySelector('#persona-reset').click();");phase=9;}
        if(phase==9&&revision(2)&&loaded(PersonaSmokeServer.a,"")){store("default",probe);edit(PersonaSmokeServer.A_FINAL,true);phase=10;}
        if(phase==10&&revision(3)&&loaded(PersonaSmokeServer.a,PersonaSmokeServer.A_FINAL)){edit(PersonaSmokeServer.DRAFT,false);phase=11;}
        if(phase==11&&!busy){busy=true;UiClientSessions.command("persona.read",Map.of("agentId",PersonaSmokeServer.foreign.toString()),UUID.randomUUID()).thenCompose(r->{if(r.code()!=Code.PERMISSION_DENIED)throw new IllegalStateException("PERSONA_FOREIGN_READ_ALLOWED");store("foreign-read",r);return UiClientSessions.command("persona.save",Map.of("agentId",PersonaSmokeServer.foreign.toString(),"expectedRevision","1","text","unauthorized"),UUID.randomUUID());}).whenComplete((r,e)->mc.execute(()->{if(e!=null||r.code()!=Code.PERMISSION_DENIED){fail(e==null?new IllegalStateException("PERSONA_FOREIGN_SAVE_ALLOWED"):e);return;}store("foreign-save",r);PersonaSmokeServer.grant=true;busy=false;phase=12;}));}
        if(phase==12&&PersonaSmokeServer.granted&&!busy){busy=true;UiClientSessions.command("persona.save",Map.of("agentId",PersonaSmokeServer.foreign.toString(),"expectedRevision","1","text","协作者已保存"),collaboratorOperation).whenComplete((r,e)->mc.execute(()->{if(e!=null||r.code()!=Code.APPLIED){fail(e==null?new IllegalStateException("PERSONA_COLLABORATOR_SAVE_FAILED"):e);return;}store("collaborator-save",r);PersonaSmokeServer.revoke=true;busy=false;phase=13;}));}
        if(phase==13&&PersonaSmokeServer.revoked&&!busy){busy=true;UiClientSessions.command("persona.save",Map.of("agentId",PersonaSmokeServer.foreign.toString(),"expectedRevision","1","text","协作者已保存"),collaboratorOperation).thenCompose(r->{if(r.code()!=Code.PERMISSION_DENIED)throw new IllegalStateException("PERSONA_REVOKED_REPLAY_ALLOWED");store("revoked-replay",r);return UiClientSessions.command("persona.save",Map.of("agentId",PersonaSmokeServer.a.toString(),"expectedRevision","1","text","stale edit"),UUID.randomUUID());}).whenComplete((r,e)->mc.execute(()->{if(e!=null||!"STALE_REVISION".equals(r.values().get("errorCode"))){fail(e==null?new IllegalStateException("PERSONA_STALE_SAVE_ALLOWED"):e);return;}store("stale-save",r);busy=false;phase=15;after=ticks+40;}));}
        if(phase==30&&loaded(PersonaSmokeServer.a,PersonaSmokeServer.DRAFT)&&!busy){busy=true;store("restored-draft",probe);UiClientSessions.command("persona.save",Map.of("agentId",PersonaSmokeServer.a.toString(),"expectedRevision","2","text",PersonaSmokeServer.A_FINAL),PersonaSmokeServer.replayOperation).whenComplete((r,e)->mc.execute(()->{try{if(e!=null||r.code()!=Code.APPLIED||!JsonParser.parseString(r.values().get("state")).getAsJsonObject().get("duplicate").getAsBoolean())throw new IllegalStateException("PERSONA_RESTART_REPLAY_FAILED",e);store("restarted-replay",r);busy=false;phase=15;after=ticks+30;}catch(Exception x){fail(x);}}));}
        if(phase==15&&ticks>=after){if(PersonaSmokeServer.stage().equals("prepare")&&!PersonaSmokeServer.operatorRestored){PersonaSmokeServer.testOperator=true;phase=40;}else{store("gui",probe);PersonaSmokeServer.finish=true;phase=16;}}
        if(phase==40&&PersonaSmokeServer.operatorReady&&!busy){busy=true;UiClientSessions.command("persona.save",Map.of("agentId",PersonaSmokeServer.foreign.toString(),"expectedRevision","1","text","协作者已保存"),collaboratorOperation).thenCompose(r->{if(r.code()!=Code.PERMISSION_DENIED)throw new IllegalStateException("PERSONA_OP_BYPASSED_OWNERSHIP");store("operator-replay-denied",r);return UiClientSessions.command("persona.read",Map.of("agentId",PersonaSmokeServer.foreign.toString()),UUID.randomUUID());}).whenComplete((r,e)->mc.execute(()->{if(e!=null||r.code()!=Code.PERMISSION_DENIED){fail(e==null?new IllegalStateException("PERSONA_OP_READ_ALLOWED"):e);return;}store("operator-read-denied",r);PersonaSmokeServer.restoreOperator=true;busy=false;phase=41;}));}
        if(phase==41&&PersonaSmokeServer.operatorRestored){phase=15;after=ticks+10;}
        if(phase==16&&PersonaSmokeServer.done&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),img->{try(img){img.writeToFile(root().resolve(PersonaSmokeServer.stage()+"-gui.png"));captured=true;}catch(Exception e){mc.execute(()->fail(e));}});}
        if(phase==16&&captured){host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Persona fixture complete"));phase=90;after=ticks+20;}
    }
}
