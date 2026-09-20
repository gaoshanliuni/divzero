package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.mineagent.runtime.api.model.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.PackageGenerationJob;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit local Provider + real GUI fixture. Does not repair or replay any real paid generation. */
public final class GenerationRepairSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();private static final String RUN=UUID.randomUUID().toString();
    private static final String INITIAL="GENERATION_REPAIR_SEED",CORRECTION="KEEP_REPAIR_INTENT：修复完整传输结构，不改变独立 RULE 与中文网页目标。";
    private static final AtomicInteger calls=new AtomicInteger();private static final AtomicBoolean barrierEntered=new AtomicBoolean(),inflightEntered=new AtomicBoolean();
    private static final CountDownLatch barrierRelease=new CountDownLatch(1),inflightRelease=new CountDownLatch(1);
    private static HttpServer provider;private static String seedRaw,validRaw;private static UUID agent,queuedCancel,queuedRevoke,inflight;
    private static volatile PackageGenerationJob source,repaired;private static CompletableFuture<?> barrier;private static int phase,started,after;private static boolean stopped,verified;private static final Set<UUID> done=new HashSet<>();
    public static boolean enabled(){return Boolean.getBoolean("mineagent.generationRepairSmoke");}
    private static Path root(MinecraftServer s){return s.getServerDirectory().resolve("generation-repair-evidence");}
    private static void write(MinecraftServer s,String name,Object value)throws Exception{Files.createDirectories(root(s));Files.writeString(root(s).resolve(name),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static String output(boolean invalid)throws Exception{
        var manifest=new LinkedHashMap<String,Object>();manifest.putAll(Map.of("name","修复测试 · 非真实模型","version","1","type","CONTENT","activationMode","HOT_RUNTIME","permissions",List.of("RUN_CODE"),"dependencies",Map.of(),"entrypoints",Map.of("server",Map.of("path","server/main.js","side","SERVER"),"server.restore",Map.of("path","server/main.js","side","SERVER"),"ui",Map.of("path","ui/index.html","side","CLIENT")),"definitions",List.of(Map.of("definitionId","11111111-1111-1111-1111-111111111111","name","repair fixture","kind","RULE","entrypointId","server","resourcePaths",List.of(),"settingsSchema",Map.of(),"revision",1,"stateSchemaVersion",1))));if(invalid)manifest.put("ui",Map.of("wrong_location",true));
        return JSON.writeValueAsString(Map.of("manifest",manifest,"files",List.of(Map.of("path","server/main.js","side","SERVER","mediaType","application/javascript","encoding","utf8","content","on('instance.create',function(){});on('instance.restore',function(){});"),Map.of("path","ui/index.html","side","CLIENT","mediaType","text/html","encoding","utf8","content","<!doctype html><meta charset='utf-8'><title>受控修复</title><h1>修复测试候选</h1><p>KEEP_REPAIR_INTENT · 未绑定业务，不是模型应用。</p>"))));
    }
    private static void provider(MinecraftServer s)throws Exception{
        seedRaw=output(true);validRaw=output(false);provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/v1/chat/completions",exchange->{try{
            var input=JSON.readTree(exchange.getRequestBody().readAllBytes());String prompt=input.path("messages").get(0).path("content").asText();int count=calls.incrementAndGet();write(s,"provider-request-"+count+".json",input);
            String answer;
            if(prompt.contains("GENERATION_REPAIR_QUEUE_BARRIER")){barrierEntered.set(true);require(barrierRelease.await(90,TimeUnit.SECONDS),"REPAIR_BARRIER_TIMEOUT");answer="CONTROLLED_BARRIER_RELEASED";}
            else if(prompt.contains("UNPUBLISHED_GENERATION_REPAIR_DATA:")){
                var data=JSON.readTree(prompt.substring(prompt.indexOf("UNPUBLISHED_GENERATION_REPAIR_DATA:")+"UNPUBLISHED_GENERATION_REPAIR_DATA:".length()));
                require(data.path("untrusted_raw_output").asText().equals(seedRaw)&&data.path("source_raw_sha256").asText().equals(source.rawOutputSha256()),"REPAIR_RAW_NOT_PRESERVED");
                require(data.path("repair_request").asText().contains("KEEP_REPAIR_INTENT"),"REPAIR_INSTRUCTIONS_MISSING");
                if(data.path("repair_request").asText().contains("IN_FLIGHT_TEST")){inflightEntered.set(true);require(inflightRelease.await(90,TimeUnit.SECONDS),"REPAIR_INFLIGHT_TIMEOUT");}
                answer=validRaw;
            }else{require(prompt.contains(INITIAL)&&count==1,"UNEXPECTED_REPAIR_PROVIDER_REQUEST");answer=seedRaw;}
            byte[] bytes=JSON.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",answer)))));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){try{write(s,"provider-failure.json",Map.of("code",failure.getClass().getSimpleName(),"message",String.valueOf(failure.getMessage())));}catch(Exception ignored){}}finally{exchange.close();}});provider.start();
    }
    public static void tick(MinecraftServer s)throws Exception{
        if(!enabled()||stopped)return;try{
            if(started!=0)require(s.getTickCount()-started<2600,"GENERATION_REPAIR_FIXTURE_TIMEOUT_"+phase);
            // Once business assertions finish, completion/teardown must not depend on still-connected viewers.
            if(phase>=7){
                if(phase==7&&done.size()==2){require(calls.get()==4,"REPAIR_DUPLICATE_BILLED");provider.stop(0);provider=null;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_SERVER_OK generationRepair={}",RUN);phase=8;after=s.getTickCount()+50;}
                if(phase==8&&s.getTickCount()>=after){stopped=true;s.halt(false);}return;
            }
            var owner=s.getPlayerList().getPlayers().stream().filter(p->p.nameAndId().name().equals("DeliveryA")).findFirst().orElse(null);
            var peer=s.getPlayerList().getPlayers().stream().filter(p->p.nameAndId().name().equals("DeliveryB")).findFirst().orElse(null);if(owner==null||peer==null)return;
            if(started==0)started=s.getTickCount();require(s.getTickCount()-started<2600,"GENERATION_REPAIR_FIXTURE_TIMEOUT_"+phase);
            var runtime=ServerPackageRuntime.get(s);
            if(phase==0){provider(s);var config=MineAgentRuntimeServices.config(s);require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false","provider.openai.baseUrl","http://127.0.0.1:"+provider.getAddress().getPort()+"/v1/","provider.openai.model","controlled-generation-repair","provider.openai.apiKey","fixture-only")),true).accepted(),"REPAIR_CONFIG");
                agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("Repair fixture Actor",owner.getUUID(),s.overworld(),owner.position().add(2,0,0)).agentId();source=runtime.submit(owner,agent,UUID.randomUUID(),INITIAL,"WORLD_CONTENT").job();phase=1;
            }
            if(phase==1){var current=runtime.generation(owner.getUUID(),source.operationId()).orElseThrow();if(current.state().equals("GENERATING"))return;require(current.state().equals("FAILED")&&!current.rawOutputSha256().isEmpty(),"REPAIR_SOURCE_NOT_FAILED");source=current;write(s,"original-failed.json",source);phase=2;}
            if(phase==2){repaired=runtime.list(owner.getUUID()).stream().filter(j->j.repairSource()!=null&&j.state().equals("PUBLISHED")).findFirst().orElse(null);if(repaired==null)return;
                require(calls.get()==2&&repaired.repairSource().operationId().equals(source.operationId())&&!repaired.packageId().equals(source.packageId())&&!runtime.worldLibrary().get(repaired.packageId()).orElseThrow().enabled(),"REPAIR_NATIVE_PUBLICATION");
                boolean denied=false;try{runtime.repair(peer,agent,UUID.randomUUID(),source.operationId(),source.revision(),source.rawOutputSha256(),CORRECTION,true);}catch(SecurityException expected){denied=true;}require(denied,"REPAIR_PEER_SOURCE_ACCEPTED");
                write(s,"published.json",repaired);barrier=MineAgentRuntimeServices.worker(s).complete(MineAgentRuntimeServices.config(s),new ModelRequest(ModelCapability.SEMANTIC,"GENERATION_REPAIR_QUEUE_BARRIER"),()->true);phase=3;
            }
            if(phase==3&&barrierEntered.get()){
                queuedCancel=UUID.randomUUID();var cancelled=runtime.repair(owner,agent,queuedCancel,source.operationId(),source.revision(),source.rawOutputSha256(),CORRECTION+" QUEUED_CANCEL",true).job();runtime.cancel(owner.getUUID(),queuedCancel);
                queuedRevoke=UUID.randomUUID();var revoked=runtime.repair(owner,agent,queuedRevoke,source.operationId(),source.revision(),source.rawOutputSha256(),CORRECTION+" QUEUED_REVOKE",true).job();var t=MineAgentRuntimeServices.tasks(s).get(revoked.taskId()).orElseThrow();require(MineAgentRuntimeServices.tasks(s).revokeAuthority(t.taskId(),t.revision()).accepted(),"REPAIR_REVOKE_TASK");
                require(calls.get()==3&&!barrier.isDone(),"REPAIR_QUEUE_NOT_BLOCKED");barrierRelease.countDown();phase=4;
            }
            if(phase==4&&barrier.isDone()){
                var cancelled=runtime.generation(owner.getUUID(),queuedCancel).orElseThrow();var revoked=runtime.generation(owner.getUUID(),queuedRevoke).orElseThrow();if(revoked.state().equals("GENERATING"))return;
                require(calls.get()==3&&cancelled.state().equals("CANCELLED")&&revoked.state().equals("STALE")&&cancelled.rawOutputSha256().isEmpty()&&revoked.rawOutputSha256().isEmpty(),"REPAIR_QUEUED_DISPATCH_OCCURRED");
                inflight=UUID.randomUUID();runtime.repair(owner,agent,inflight,source.operationId(),source.revision(),source.rawOutputSha256(),CORRECTION+" IN_FLIGHT_TEST",true);phase=5;
            }
            if(phase==5&&inflightEntered.get()){runtime.cancel(owner.getUUID(),inflight);inflightRelease.countDown();phase=6;}
            if(phase==6){var late=runtime.generation(owner.getUUID(),inflight).orElseThrow();if(late.rawOutputSha256().isEmpty())return;
                require(late.state().equals("CANCELLED")&&runtime.worldLibrary().get(late.packageId()).isEmpty()&&calls.get()==4,"REPAIR_LATE_PUBLICATION");require(runtime.generation(owner.getUUID(),source.operationId()).orElseThrow().equals(source),"REPAIR_CHANGED_ORIGINAL");
                require(runtime.submit(owner,agent,source.operationId(),source.prompt(),"WORLD_CONTENT").duplicate(),"REPAIR_REPLAYED_SEED");require(runtime.repair(owner,agent,repaired.operationId(),source.operationId(),source.revision(),source.rawOutputSha256(),repaired.prompt(),true).duplicate(),"REPAIR_DUPLICATE_REGENERATED");
                verified=true;write(s,"result.json",Map.of("status","NATIVE_GUI_REPAIR_LINEAGE_AND_SINGLE_DISPATCH_VERIFIED","original",source,"repaired",repaired,"queuedCancelled",runtime.generation(owner.getUUID(),queuedCancel).orElseThrow(),"queuedRevoked",runtime.generation(owner.getUUID(),queuedRevoke).orElseThrow(),"lateCancelled",late,"controlledProviderCalls",calls.get(),"realModelCalls",0,"systemInputInjected",false,"nativeActivation",false));phase=7;
            }
        }catch(Exception failure){stopped=true;barrierRelease.countDown();inflightRelease.countDown();if(provider!=null)provider.stop(0);write(s,"failure.json",Map.of("phase",phase,"error",failure.toString(),"calls",calls.get()));s.halt(false);throw failure;}
    }
    public static void handle(ServerPlayer player,UiPayloads.Command packet)throws Exception{
        var s=player.level().getServer();var n=JSON.readTree(packet.json());String action=n.path("action").asText();if(n.size()!=1||!Set.of("info","done").contains(action))throw new IllegalArgumentException("GENERATION_REPAIR_FIXTURE_ACTION");if(action.equals("done"))done.add(player.getUUID());
        boolean owner=player.nameAndId().name().equals("DeliveryA");var info=new LinkedHashMap<String,Object>();info.put("run",RUN);info.put("ready",agent!=null);info.put("owner",owner);info.put("verified",verified);info.put("calls",calls.get());info.put("sourceReady",phase>=2);if(owner&&source!=null){info.put("sourceOperation",source.operationId());info.put("instructions",CORRECTION);if(repaired!=null)info.put("repairedTask",repaired.taskId());}
        PacketDistributor.sendToPlayer(player,new UiPayloads.Event(packet.requestId(),"deliveryFixture",JSON.writeValueAsString(info)));
    }
}
