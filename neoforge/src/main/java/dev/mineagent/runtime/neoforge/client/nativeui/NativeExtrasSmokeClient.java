package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Explicit isolated zero-model acceptance of trusted choices and the full building editor packet path. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeExtrasSmokeClient {
    private static final Gson JSON=new Gson();private static final List<Object> evidence=new ArrayList<>();
    private static UUID agent;private static DecisionRequest question;private static int ticks,stage,wait;private static boolean busy,done,trustPrepared;
    private static Path output()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("native-extras-smoke"));}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeExtrasSmoke")||done)return;var mc=Minecraft.getInstance();
        try{
            if(ticks%100==0)Files.writeString(output().resolve("progress.json"),JSON.toJson(Map.of("stage",stage,"ticks",ticks,"ready",NativeWorkspaceConnection.ready(),"enabled",dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())));if(++ticks>6000)throw new IllegalStateException("NATIVE_EXTRAS_TIMEOUT_"+stage);if(mc.player==null||mc.getSingleplayerServer()==null||busy)return;
            if(!trustPrepared){var values=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();String fingerprint=values.getOrDefault("security.identityFingerprint","");if(fingerprint.isBlank()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(values.get("security.identityPublicKey")));trustPrepared=true;}
            if(stage==0){server(p->{var server=p.level().getServer();server.getPlayerList().op(p.nameAndId());dev.mineagent.runtime.neoforge.WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("NativeExtras",p.getUUID(),p.level(),p.position().add(-3,0,0)).agentId();question=new DecisionRequest(UUID.randomUUID(),1,p.getUUID(),0,DecisionKind.AUTHORIZATION,"原生授权选择","选择仅提交回答；关闭窗口不会授权。",List.of(new DecisionOption("allow","允许","明确选择才生效"),new DecisionOption("deny","拒绝","保留当前状态")),SelectionMode.SINGLE,1,1,true,DecisionStatus.OPEN);MineAgentRuntimeServices.decisions(server).open(question);return Map.of("question",question);},1);return;}
            if(stage==1){NativeWorkspaceScreen.openForAgent(agent.toString(),"NativeExtras");if(!NativeWorkspaceConnection.ready())return;NativeDecisionPanel.smokeOpen((NativeWorkspaceScreen)mc.screen,question);stage=2;return;}
            if(stage==2){if(++wait<25)return;wait=0;NativeDecisionPanel.smokeEdit(question.decisionId(),null,"我已阅读 🌲");NativeDecisionPanel.smokeAction(question.decisionId(),"submit");require(NativeDecisionPanel.smokeState(question.decisionId()).get("notice").toString().contains("AUTHORIZATION_CHOICE_REQUIRED"),"FREE_TEXT_AUTHORIZED");NativeDecisionPanel.smokeEdit(question.decisionId(),"deny",null);NativeDecisionPanel.smokeClose(question.decisionId());server(p->{require(MineAgentRuntimeServices.decisions(p.level().getServer()).acceptedAnswer(question.decisionId()).isEmpty(),"CLOSE_SUBMITTED");return Map.of("freeTextRejected",true,"closeDidNotSubmit",true);},3);return;}
            if(stage==3){NativeDecisionPanel.smokeOpen((NativeWorkspaceScreen)mc.screen,question);require(NativeDecisionPanel.smokeState(question.decisionId()).get("custom").equals("我已阅读 🌲"),"CLOSE_LOST_DRAFT");NativeDecisionPanel.smokeAction(question.decisionId(),"defer");stage=4;return;}
            if(stage==4){if(!NativeDecisionPanel.smokeState(question.decisionId()).get("status").equals("DEFERRED"))return;require(NativeDecisionPanel.smokeState(question.decisionId()).get("custom").equals("我已阅读 🌲"),"DEFER_LOST_DRAFT");NativeDecisionPanel.smokeAction(question.decisionId(),"resume");stage=5;return;}
            if(stage==5){if(!NativeDecisionPanel.smokeState(question.decisionId()).get("status").equals("OPEN"))return;NativeDecisionPanel.smokeAction(question.decisionId(),"submit");stage=6;return;}
            if(stage==6){if(!NativeDecisionPanel.smokeState(question.decisionId()).get("status").equals("RESOLVED"))return;server(p->{var answer=MineAgentRuntimeServices.decisions(p.level().getServer()).acceptedAnswer(question.decisionId()).orElseThrow();require(answer.selectedOptionIds().equals(List.of("deny"))&&answer.customText().equals("我已阅读 🌲"),"ACCEPTED_ANSWER_MISMATCH");return Map.of("acceptedAnswer",answer,"acceptedIsNotDomainEffect",MineAgentRuntimeServices.decisions(p.level().getServer()).domainEffect(question.decisionId()).isEmpty());},7);return;}
            if(stage==7){if(++wait<20)return;wait=0;screenshot("01-native-decision.png");NativeDecisionPanel.smokeClose(question.decisionId());stage=8;return;}
            if(stage==8){String source=largeDesign();require(source.length()>24000,"NOT_LARGE_DOCUMENT");busy=true;NativeBuildingPanel.smokeRoundTrip((NativeWorkspaceScreen)mc.screen,agent.toString(),source).whenComplete((read,error)->{busy=false;if(error!=null){fail(error);return;}try{require(JsonParser.parseString(read).equals(JsonParser.parseString(source)),"BUILDING_EDITOR_ROUNDTRIP_CHANGED");evidence.add(Map.of("buildingEditorPacketRoundTrip",true,"sourceChars",source.length(),"sourceBytes",source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"unicodeRetained",read.contains("🌲"),"components",200));stage=9;}catch(Exception invalid){fail(invalid);}});return;}
            if(stage==9){if(++wait<25)return;wait=0;screenshot("02-native-large-building-editor.png");stage=10;return;}
            if(stage==10){Files.writeString(output().resolve("result.json"),JSON.toJson(Map.of("status","PASS","modelCalls",0,"kubejsInstalled",net.neoforged.fml.ModList.get().isLoaded("kubejs"),"evidence",evidence)));done=true;mc.stop();}
        }catch(Exception error){fail(error);}
    }
    private static String largeDesign(){var mc=Minecraft.getInstance();var design=new JsonObject();design.addProperty("id","large_editor");design.addProperty("name","原生大文档 🌲");design.addProperty("dimension",mc.level.dimension().identifier().toString());design.add("origin",JSON.toJsonTree(List.of(mc.player.blockPosition().getX(),mc.player.blockPosition().getY()+12,mc.player.blockPosition().getZ())));var components=new JsonArray();design.add("components",components);for(int i=0;i<200;i++){var component=new JsonObject();component.addProperty("id","window_"+i);component.addProperty("name","独立窗组 🌲 "+i);var parts=new JsonArray();var part=new JsonObject();part.addProperty("kind","box");part.addProperty("material","minecraft:stone");part.add("min",JSON.toJsonTree(List.of(i%20,0,i/20)));part.add("max",JSON.toJsonTree(List.of(i%20,0,i/20)));parts.add(part);component.add("parts",parts);components.add(component);}return JSON.toJson(design);}
    private static void server(Function<ServerPlayer,Object> action,int next){busy=true;var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();var owner=mc.player.getUUID();server.submit(()->action.apply(server.getPlayerList().getPlayer(owner))).whenComplete((value,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}evidence.add(value);stage=next;}));}
    private static void screenshot(String name)throws Exception{var mc=Minecraft.getInstance();net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try{image.writeToFile(output().resolve(name));}catch(Exception error){fail(error);}finally{image.close();}});}
    private static void fail(Throwable error){if(done)return;done=true;try{Files.writeString(output().resolve("failure.json"),JSON.toJson(Map.of("stage",stage,"error",error.toString(),"evidence",evidence)));}catch(Exception ignored){}Minecraft.getInstance().stop();}
    private NativeExtrasSmokeClient(){}
}
