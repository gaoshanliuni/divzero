package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.*;
import java.util.*;

/** Opt-in three-JVM production fixture. Faults are staged at durable boundaries, not simulated Native successes. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class YsmAppearanceRecoveryServer {
    public static volatile Map<String,String> clientPlan=Map.of();
    public static volatile boolean finishRequested,finished;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static Map<String,String> journal;
    private static int finishTick=-1;
    private static int unknownStagedAt=-1;
    private static boolean ready,positioned;
    private static final Set<String> observedSessions=new LinkedHashSet<>();
    public static boolean enabled(){return Boolean.getBoolean("mineagent.appearanceRecoverySmoke");}
    public static String stage(){return System.getProperty("mineagent.appearanceRecoveryStage","");}
    private static Path root(MinecraftServer server){return server.getServerDirectory().resolve("appearance-recovery-evidence");}

    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||finished)return;var server=event.getServer();
        var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var bodies=MineAgentRuntimeServices.bodies(server);var decisions=MineAgentRuntimeServices.decisions(server);
        ServerUiRuntime.get(server).sessions().list(viewer.getUUID()).forEach(s->observedSessions.add(s.sessionId().toString()));
        var bridge=new NeoForgeYsmRuntimeBridge(server);if(!bridge.runtimeAvailable())return;
        Files.createDirectories(root(server));
        if(journal==null){
            if(stage().equals("prepare")){
                if(Files.exists(root(server).resolve("journal.json")))throw new IllegalStateException("RECOVERY_REQUIRES_FRESH_PROFILE");
                var a=bodies.create("Appearance Recovery Main",viewer);var b=bodies.create("Appearance Recovery Outbox",viewer);
                journal=new LinkedHashMap<>(Map.of("agent",a.agentId().toString(),"outboxAgent",b.agentId().toString(),"viewer",viewer.getUUID().toString(),"world",MineAgentRuntimeServices.worldId(server).toString(),"preparePid",Long.toString(ProcessHandle.current().pid())));
                journal.put("initial",open(viewer,a.agentId(),0,"blue").decisionId().toString());
                clientPlan=Map.copyOf(journal);
            }else{
                journal=JSON.readValue(Files.readString(root(server).resolve("journal.json")),new TypeReference<LinkedHashMap<String,String>>(){});
                if(!journal.get("world").equals(MineAgentRuntimeServices.worldId(server).toString())||!journal.get("viewer").equals(viewer.getUUID().toString())||journal.get("preparePid").equals(Long.toString(ProcessHandle.current().pid())))throw new IllegalStateException("RECOVERY_SCOPE_OR_PROCESS_MISMATCH");
                for(String key:List.of("agent","outboxAgent")){
                    var definition=bodies.definitions().stream().filter(d->d.agentId().equals(id(key))).findFirst().orElseThrow();
                    if(!definition.ownerPlayerId().equals(viewer.getUUID()))throw new IllegalStateException("RECOVERY_PERSISTED_AGENT_MISSING");
                }
            }
        }
        // The real Native PrepareSpawnTask restores saved bodies after their chunks are ready.
        if(List.of("agent","outboxAgent").stream().anyMatch(key->bodies.body(id(key)).isEmpty()))return;
        if(!positioned){
            positioned=true;var body=bodies.body(id("agent")).orElseThrow();var pos=body.blockPosition();
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)body.level().setBlockAndUpdate(pos.offset(x,-1,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();
            viewer.teleportTo(body.level(),body.getX()+3,body.getY()+2,body.getZ()+7,Set.of(),156,12,true);
        }
        if(!stage().equals("prepare")&&!ready){
            var unknown=decisions.domainEffect(id("unknown")).orElseThrow();
            if(!unknown.state().equals("INTERRUPTED")||!unknown.error().equals("NATIVE_OUTCOME_UNKNOWN"))throw new IllegalStateException("UNKNOWN_EFFECT_REPLAYED");
            var outbox=decisions.domainEffect(id("outbox")).orElse(null);if(outbox==null||outbox.state().equals("APPLYING"))return;
            if(!outbox.state().equals("APPLIED")||outbox.resultRevision()!=1)throw new IllegalStateException("OUTBOX_RECOVERY_FAILED: "+outbox);
            checkNative(viewer,bridge,"outboxAgent",1,"blue");
            checkNative(viewer,bridge,"agent",stage().equals("resume")?2:3,stage().equals("resume")?"default":"blue");
            if(stage().equals("resume")&&decisions.get(id("pending")).orElseThrow().status()!=DecisionStatus.OPEN)throw new IllegalStateException("PENDING_DECISION_NOT_RESTORED");
            Files.writeString(root(server).resolve(stage()+"-before.json"),JSON.writeValueAsString(snapshot(viewer,bridge)));
            ready=true;clientPlan=Map.copyOf(journal);
        }
        if(!finishRequested)return;
        if(stage().equals("prepare")){
            if(unknownStagedAt<0){
            var initial=decisions.domainEffect(id("initial")).orElseThrow();if(!initial.state().equals("APPLIED"))throw new IllegalStateException("INITIAL_UI_NOT_APPLIED");
            checkNative(viewer,bridge,"agent",1,"blue");
            var unknown=open(viewer,id("agent"),1,"default");journal.put("unknown",unknown.decisionId().toString());
            var answer=new DecisionAnswerSubmission(unknown.decisionId(),1,UUID.randomUUID(),List.of("typed"),"",AnswerSource.UI);
            if(!decisions.submitForTask(viewer.getUUID(),MineAgentRuntimeServices.tasks(server),answer).accepted()||!decisions.beginDomainEffect(unknown.decisionId(),viewer.getUUID(),answer.submissionId()))throw new IllegalStateException("UNKNOWN_BOUNDARY_NOT_STAGED");
            var actual=MineAgentNetwork.applyAppearanceFromUi(new MineAgentPayloads.AppearanceCommand(id("agent").toString(),"default","default","idle",1,answer.submissionId().toString()),viewer);
            if(!actual.accepted()||actual.revision()!=2)throw new IllegalStateException("UNKNOWN_NATIVE_NOT_APPLIED");
            // Deliberately omit finishDomainEffect after the real Native result, modelling loss of its durable receipt.
            unknownStagedAt=server.getTickCount();return;
            }
            // Drain real Native packets first, then stage the unstarted outbox immediately after a reconciler tick.
            // The launcher verifies the SQLite state after this JVM is fully closed, before starting the next one.
            if(server.getTickCount()-unknownStagedAt<20||server.getTickCount()%20!=1)return;
            journal.put("pending",open(viewer,id("agent"),2,"blue").decisionId().toString());
            journal.put("stale",open(viewer,id("agent"),2,"blue").decisionId().toString());
            var outbox=open(viewer,id("outboxAgent"),0,"blue");journal.put("outbox",outbox.decisionId().toString());
            if(!decisions.submitForTask(viewer.getUUID(),MineAgentRuntimeServices.tasks(server),new DecisionAnswerSubmission(outbox.decisionId(),1,UUID.randomUUID(),List.of("typed"),"",AnswerSource.UI)).accepted())throw new IllegalStateException("OUTBOX_NOT_ACCEPTED");
            if(decisions.domainEffect(outbox.decisionId()).isPresent())throw new IllegalStateException("OUTBOX_ALREADY_STARTED");
            Files.writeString(root(server).resolve("journal.json"),JSON.writeValueAsString(journal));
            Files.writeString(root(server).resolve("prepare-boundaries.json"),JSON.writeValueAsString(snapshot(viewer,bridge)));
            finish(server);return;
        }
        if(finishTick<0)finishTick=server.getTickCount();
        if(server.getTickCount()-finishTick<80)return;
        checkNative(viewer,bridge,"agent",3,"blue");checkNative(viewer,bridge,"outboxAgent",1,"blue");
        if(!decisions.domainEffect(id("pending")).orElseThrow().state().equals("APPLIED")||!decisions.acceptedAnswer(id("pending")).orElseThrow().source().equals(AnswerSource.CHAT))throw new IllegalStateException("RESTORED_CHAT_NOT_APPLIED");
        var stale=decisions.domainEffect(id("stale")).orElseThrow();if(!stale.state().equals("FAILED")||!stale.error().equals("STALE_REVISION"))throw new IllegalStateException("STALE_APPEARANCE_NOT_REJECTED");
        var bodiesBefore=bodyIdentity(server);
        for(String key:List.of("initial","pending","stale","unknown","outbox")){
            var q=decisions.get(id(key)).orElseThrow();var answer=decisions.acceptedAnswer(q.decisionId()).orElseThrow();
            var replay=decisions.submitForTask(viewer.getUUID(),MineAgentRuntimeServices.tasks(server),answer);if(!replay.accepted()||!replay.duplicate())throw new IllegalStateException("PERSISTED_REPLAY_IDENTITY_LOST");
            MineAgentNetwork.applyUiDecisionEffects(viewer,answer,q);
        }
        if(!bodiesBefore.equals(bodyIdentity(server)))throw new IllegalStateException("REPLAY_REPLACED_OR_DUPLICATED_BODY");
        checkNative(viewer,bridge,"agent",3,"blue");checkNative(viewer,bridge,"outboxAgent",1,"blue");
        Files.writeString(root(server).resolve(stage()+"-after.json"),JSON.writeValueAsString(snapshot(viewer,bridge)));finish(server);
    }
    private static UUID id(String key){return UUID.fromString(journal.get(key));}
    private static DecisionRequest open(ServerPlayer viewer,UUID agent,long revision,String texture)throws Exception{
        return MineAgentNetwork.openAppearanceDecisionFromUi(viewer,agent,revision,"default",texture,"idle",UUID.randomUUID());
    }
    private static void checkNative(ServerPlayer viewer,NeoForgeYsmRuntimeBridge bridge,String key,long revision,String texture){
        var state=MineAgentNetwork.readAppearanceFromUi(viewer,id(key));var nativeState=bridge.currentSelection(id(key)).orElseThrow();
        if(((Number)state.get("revision")).longValue()!=revision||!state.get("model").equals("default")||!state.get("texture").equals(texture)||!nativeState.modelId().equals("default")||!nativeState.textureId().equals(texture))throw new IllegalStateException("RECOVERY_NATIVE_STATE: "+key+" "+state+" "+nativeState);
    }
    private static Map<String,Object> snapshot(ServerPlayer viewer,NeoForgeYsmRuntimeBridge bridge){
        var server=viewer.level().getServer();var service=MineAgentRuntimeServices.decisions(server);var records=new LinkedHashMap<String,Object>();
        for(String key:List.of("initial","pending","stale","unknown","outbox"))if(journal.containsKey(key)){
            var record=new LinkedHashMap<String,Object>();record.put("question",service.get(id(key)).orElseThrow());record.put("context",service.domainContext(id(key)));record.put("answer",service.acceptedAnswer(id(key)).orElse(null));record.put("effect",service.domainEffect(id(key)).orElse(null));records.put(key,record);
        }
        return Map.of("pid",ProcessHandle.current().pid(),"stage",stage(),"journal",journal,"records",records,"main",MineAgentNetwork.readAppearanceFromUi(viewer,id("agent")),"outboxAgent",MineAgentNetwork.readAppearanceFromUi(viewer,id("outboxAgent")),"nativeMain",bridge.currentSelection(id("agent")).orElseThrow(),"bodies",bodyIdentity(server),"sessions",List.copyOf(observedSessions),"mode","DURABLE_BOUNDARY_FAULT_INJECTION_WITH_REAL_NATIVE_AND_UI_NOT_PROVIDER");
    }
    private static Map<String,Integer> bodyIdentity(MinecraftServer server){
        var result=new LinkedHashMap<String,Integer>();
        for(String key:List.of("agent","outboxAgent")){
            var body=MineAgentRuntimeServices.bodies(server).body(id(key)).orElseThrow();
            if(server.getPlayerList().getPlayers().stream().filter(p->p.getUUID().equals(id(key))).count()!=1||!body.getUUID().equals(id(key)))throw new IllegalStateException("DUPLICATE_OR_WRONG_BODY");
            result.put(id(key).toString(),body.getId());
        }
        return Map.copyOf(result);
    }
    private static void finish(MinecraftServer server){
        finished=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_APPEARANCE_RECOVERY_{}_OK",stage().toUpperCase(Locale.ROOT));
    }
}
