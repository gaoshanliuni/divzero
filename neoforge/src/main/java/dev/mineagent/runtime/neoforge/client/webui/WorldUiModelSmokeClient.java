package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.WorldUiModelSmokeServer;
import dev.mineagent.runtime.neoforge.client.objects.RuntimeObjectRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Real GUI generation/consent, physical interaction and generated handlers. Not an AI operator claim. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldUiModelSmokeClient {
    private static final Gson JSON=new Gson();
    private static int ticks,phase,after;private static boolean backup,trusted,trustPressed,opened,busy;private static volatile boolean captured;
    private static String view;private static UUID session;private static JsonObject probe;
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldUiModelSmokeServer.directory());}
    public static void accept(JsonObject value){probe=value;}
    private static String q(Object value){return JSON.toJson(value);}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static void frame(String code){var h=WebGuiHostAdapter.INSTANCE;String url=h.packageUrl(view);for(long id:h.browser().getFrameIdentifiers()){var f=h.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript("(()=>{"+code+"})();",url,0);return;}}throw new IllegalStateException("MODEL_WORLD_UI_FRAME_MISSING");}
    private static void shot(String name){captured=false;var mc=Minecraft.getInstance();net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name));captured=true;}catch(Exception e){mc.execute(()->fail(e));}});}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!WorldUiModelSmokeServer.enabled()||phase==99)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Files.createDirectories(root());
        if(phase==98){if(ticks>=after){phase=99;mc.stop();}return;}
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(WorldUiModelSmokeServer.failure!=null||ticks>(WorldUiModelSmokeServer.repairing()?35000:23500)){fail(new IllegalStateException("MODEL_WORLD_UI_FAILED: "+WorldUiModelSmokeServer.failure));return;}
        if(phase==10){if(ticks>=after){phase=99;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("{} run={}",WorldUiModelSmokeServer.repairing()?"MINEAGENT_WORLD_UI_REPAIR_OK":WorldUiModelSmokeServer.negative()?"MINEAGENT_WORLD_UI_FAILURE_OK":"MINEAGENT_WORLD_UI_MODEL_OK",WorldUiModelSmokeServer.RUN);mc.stop();}return;}
        if(phase==90){if(captured){Files.writeString(root().resolve("client-result.json"),q(Map.of("status","CONTROLLED_FAILURE_GUI_VERIFIED","errorCode","GENERATION_PROVIDER_HTTP_401","provider","CONTROLLED_LOCAL_HTTP_NOT_MODEL","paidProviderCalls",0,"generationOperation",WorldUiModelSmokeServer.operationId,"fullV1",false)));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Controlled generation failure verified"));phase=10;after=ticks+20;}return;}
        if(!trusted&&mc.player!=null){
            if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!trustPressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().equals("信任此服务器")){b.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});trustPressed=true;break;}
            String fingerprint=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");
            if((trustPressed||WorldUiModelSmokeServer.repairing())&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fingerprint)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;Files.writeString(root().resolve("trust.json"),q(Map.of("actualNativeTrustButton",trustPressed,"previouslyStoredTrust",!trustPressed,"fingerprint",fingerprint)));mc.setScreen(null);}else return;
        }
        if(trusted&&!opened){opened=true;if(!dev.mineagent.runtime.neoforge.ui.WorldUiRepairSmokeServer.verifyOnly())host.open();}
        if(WorldUiModelSmokeServer.repairing()&&dev.mineagent.runtime.neoforge.ui.WorldUiRepairSmokeServer.verifyOnly()){
            if(phase!=91&&WorldUiModelSmokeServer.restartVerified&&RuntimeObjectRenderer.drawn.getOrDefault(WorldUiModelSmokeServer.rotorId,0)>10){shot("restart-native.png");phase=91;}
            if(phase==91&&captured){Files.writeString(root().resolve("restart-client.json"),q(Map.of("status","REPAIRED_WORLD_UI_RESTART_VERIFIED","draws",RuntimeObjectRenderer.drawn,"providerCalls",0,"diagnosticAttachRequired",false,"fullV1",false)));mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("World UI restart verified"));phase=10;after=ticks+20;}return;
        }
        if(WorldUiModelSmokeServer.repairing()&&phase==0){
            if(!dev.mineagent.runtime.neoforge.ui.WorldUiRepairSmokeServer.ready){if(host.ready()&&UiClientSessions.current()!=null)WorldUiRepairSmokeClient.step();return;}
            phase=1;
        }
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%10==0){
            Files.writeString(root().resolve("progress.json"),q(Map.of("phase",phase,"ticks",ticks,"probe",probe==null?new JsonObject():probe)));
            main("window.mineagentQuery({request:JSON.stringify({channel:'worldUiModelProbe',text:document.body.innerText.slice(-18000),chat:!!document.querySelector('#chat-draft'),generation:!!document.querySelector('#generation-prompt'),statusWindow:!!document.querySelector('#echo-input'),windows:[...document.querySelectorAll('.window')].map(n=>({id:n.dataset.viewId,title:n.dataset.title,kind:n.dataset.contentKind}))}),persistent:false,onSuccess(){},onFailure(){}});");
            if(phase==0&&WorldUiModelSmokeServer.agentId!=null){
                main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const a=document.querySelector('#generation-agent');if(![...a.options].some(o=>o.value==="+q(WorldUiModelSmokeServer.agentId)+"))return;if(!window.__worldUiModelSubmitted){window.__worldUiModelSubmitted=true;a.value="+q(WorldUiModelSmokeServer.agentId)+";const kind=document.querySelector('#generation-purpose');kind.value='WORLD_CONTENT';kind.dispatchEvent(new Event('change',{bubbles:true}));const p=document.querySelector('#generation-prompt');p.value="+q(WorldUiModelSmokeServer.PROMPT)+";p.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#generation-submit').click();}");
                if(WorldUiModelSmokeServer.operationId!=null)phase=1;
            }
            if(phase==1&&WorldUiModelSmokeServer.allowNative)main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const open=document.querySelector('[data-world-package="'+PKG+'"]');if(open&&!document.querySelector('[data-world-activate="'+PKG+'"]'))open.click();
                const start=document.querySelector('[data-world-activate="'+PKG+'"]');if(start&&!window.__modelNativeActivated){const c=start.closest('.content');for(const[key,value]of Object.entries(LOCATION)){const f=c.querySelector('[data-world-field="'+key+'"]');if(f){f.value=value;f.dispatchEvent(new Event('change',{bubbles:true}));}}window.__modelNativeActivated=true;c.querySelector('[data-world-consent]').click();start.click();}
                """.replace("PKG",q(WorldUiModelSmokeServer.packageId)).replace("LOCATION",q(WorldUiModelSmokeServer.LOCATION)));
            if(phase==9)main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const open=document.querySelector('[data-world-package=\""+WorldUiModelSmokeServer.packageId+"\"]');if(open&&!document.querySelector('[data-world-activate]'))open.click();const stop=document.querySelector('[data-world-disable=\""+WorldUiModelSmokeServer.activationId+"\"]');if(stop&&!window.__modelNativeStopped){window.__modelNativeStopped=true;stop.click();}");
        }
        if(WorldUiModelSmokeServer.negative()&&WorldUiModelSmokeServer.expectedFailure&&probe!=null&&probe.get("text").getAsString().contains("GENERATION_PROVIDER_HTTP_401")){Files.writeString(root().resolve("failure-gui.json"),q(probe));shot("controlled-failure-gui.png");phase=90;return;}
        if(phase==1&&WorldUiModelSmokeServer.rotating&&RuntimeObjectRenderer.drawn.getOrDefault(WorldUiModelSmokeServer.rotorId,0)>10){Files.writeString(root().resolve("activation-gui.json"),q(probe));host.close();phase=2;after=ticks+20;}
        if(phase==2&&ticks>=after){shot("native-without-gui.png");phase=21;}
        if(phase==21&&captured){var e=mc.level.getEntity(WorldUiModelSmokeServer.rotorId);mc.gameMode.interact(mc.player,e,new net.minecraft.world.phys.EntityHitResult(e,e.position()),net.minecraft.world.InteractionHand.MAIN_HAND);phase=3;after=ticks+40;probe=null;}
        if(phase==3&&ticks>=after&&host.ready()&&probe!=null&&!busy&&PackageContentClient.worldViews().size()==1){
            view=PackageContentClient.worldViews().getFirst();var s=PackageContentClient.session(view);if(s==null)return;session=s.sessionId();
            if(probe.get("chat").getAsBoolean()||probe.get("generation").getAsBoolean()||probe.get("statusWindow").getAsBoolean())throw new IllegalStateException("MODEL_UI_CONTROL_PANEL_COUPLED");
            busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);Files.writeString(root().resolve("initial-dom.json"),value);
                var elements=JsonParser.parseString(value).getAsJsonObject().getAsJsonArray("elements");var controls=new HashMap<String,JsonObject>();for(var e:elements){var o=e.getAsJsonObject();controls.put(o.get("dataAiId").getAsString(),o);}
                if(!controls.keySet().containsAll(Set.of("speed","enabled","apply"))||controls.get("apply").get("disabled").getAsBoolean()){busy=false;after=ticks+20;return;}
                Files.writeString(root().resolve("standalone.json"),q(Map.of("probe",probe,"session",s)));
                WorldUiModelSmokeServer.expectation=1;
                frame("const speed=document.querySelector('[data-ai-id=speed]');speed.value='3';speed.dispatchEvent(new Event('input',{bubbles:true}));speed.dispatchEvent(new Event('change',{bubbles:true}));const enabled=document.querySelector('[data-ai-id=enabled]');if(enabled.checked)enabled.click();document.querySelector('[data-ai-id=apply]').click();");phase=4;busy=false;
            }catch(Exception e){fail(e);}}));
        }
        if(phase==4&&WorldUiModelSmokeServer.paused&&!busy){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.writeString(root().resolve("paused-dom.json"),value);PackagePageAgent.captureManagedView(view).whenComplete((pixels,captureError)->mc.execute(()->{try{if(captureError!=null)throw new IllegalStateException(captureError);Files.write(root().resolve("private-paused.png"),pixels.png());shot("generated-world-ui.png");phase=5;busy=false;}catch(Exception e){fail(e);}}));}catch(Exception e){fail(e);}}));}
        if(phase==5&&captured){main("document.querySelector('#open-chat').click();document.querySelector('[data-view-id=runtime-chat] [aria-label=关闭]').click();");phase=6;after=ticks+20;}
        if(phase==6&&ticks>=after){if(!session.equals(PackageContentClient.session(view).sessionId()))throw new IllegalStateException("MODEL_CHAT_CLOSE_REPLACED_WORLD_SESSION");WorldUiModelSmokeServer.expectation=2;frame("const enabled=document.querySelector('[data-ai-id=enabled]');if(!enabled.checked)enabled.click();document.querySelector('[data-ai-id=apply]').click();");phase=7;}
        if(phase==7&&WorldUiModelSmokeServer.resumed&&!busy){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.writeString(root().resolve("resumed-dom.json"),value);host.close();WorldUiModelSmokeServer.closedGui=true;phase=8;busy=false;}catch(Exception e){fail(e);}}));}
        if(phase==8&&WorldUiModelSmokeServer.independent){shot("continues-without-browser.png");phase=81;}
        if(phase==81&&captured){host.open();phase=9;}
        if(phase==9&&WorldUiModelSmokeServer.done){Files.writeString(root().resolve("client-result.json"),q(Map.of("status","REAL_CODER_NATIVE_UI_GRAPHICAL_VERIFIED","model",WorldUiModelSmokeServer.model,"generationOperation",WorldUiModelSmokeServer.operationId,"session",session,"draws",RuntimeObjectRenderer.drawn,"guiOperator","EXPLICIT_TEST_DRIVER_NOT_AI","fullV1",false)));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("World UI model verification complete"));phase=10;after=ticks+20;}
    }
    private static void fail(Exception e){if(phase==98||phase==99)return;try{Files.writeString(root().resolve("client-failure.json"),q(Map.of("phase",phase,"error",e.toString(),"probe",probe==null?new JsonObject():probe)));}catch(Exception ignored){}phase=98;WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().disconnectFromWorld(net.minecraft.network.chat.Component.literal("World UI acceptance failed; evidence retained"));after=ticks+20;}
}
