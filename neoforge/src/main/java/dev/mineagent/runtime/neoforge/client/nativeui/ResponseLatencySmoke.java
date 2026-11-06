package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Isolated native fixture. The HTTP server is deterministic and never calls a model service. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ResponseLatencySmoke {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<Object> evidence=new ArrayList<>();
    private static final AtomicInteger rounds=new AtomicInteger(),summaries=new AtomicInteger();
    private static volatile CountDownLatch release=new CountDownLatch(1),summaryEntered=new CountDownLatch(1);
    private static volatile Throwable failure;private static volatile int phase;
    private static HttpServer http;private static UUID agent,conversation;private static CompletableFuture<Map<String,Object>> result;private static int ticks;private static boolean polling,chatStarted;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void require(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> action){var owner=mc().player.getUUID();return mc().getSingleplayerServer().submit(()->action.apply(mc().getSingleplayerServer().getPlayerList().getPlayer(owner)));}
    private static CompletableFuture<Map<String,Object>> tool(String name,Map<String,Object> args){return server(p->{try{return ConversationAgentTools.execute(p,agent,UUID.randomUUID(),name,JSON.writeValueAsString(args),()->true);}catch(Exception e){throw new CompletionException(e);}}).thenCompose(f->f);}
    private static void event(HttpExchange e,Object delta)throws Exception{e.getResponseBody().write(("data: "+JSON.writeValueAsString(Map.of("model","latency-fixture","choices",List.of(Map.of("delta",delta))))+"\n\n").getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();}
    private static void request(HttpExchange e){try{
        JsonNode input=JSON.readTree(e.getRequestBody());
        String supplied=input.path("messages").toString();boolean summary=supplied.contains("CONVERSATION_SUMMARY_V1")||supplied.contains("Summarize only the assistant prose supplied above");
        if(!summary&&(!input.path("tools").isArray()||input.path("tools").isEmpty())){
            if(input.path("stream").asBoolean()){e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);event(e,Map.of("content","Latency fixture"));e.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));}
            else{byte[] bytes=JSON.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","Latency fixture")))));e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}return;
        }
        if(!input.path("stream").asBoolean()){
            var gate=release;summaries.incrementAndGet();summaryEntered.countDown();String prompt=input.path("messages").toString();
            require(!prompt.contains("minecraft:stone")&&!prompt.contains("tool_call_id")&&!prompt.contains("RAW_TOOL_SENTINEL"),"SUMMARY_CONTAINS_TOOL_RESULT");
            require(gate.await(25,TimeUnit.SECONDS),"SUMMARY_WAS_AWAITED_BY_MAIN_RESPONSE");
            byte[] bytes=JSON.writeValueAsBytes(Map.of("model","latency-fixture","choices",List.of(Map.of("message",Map.of("content",prompt.contains("CONVERSATION_SUMMARY_V1")?"{\"summary\":\"Assistant prose only\"}":"Assistant prose only")))));e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);return;
        }
        int round=rounds.getAndIncrement();e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);
        if(phase==0&&round<2){String tool=round==0?"scan_blocks":"observe";Object args=round==0?Map.of("blocks",List.of("minecraft:stone"),"min",List.of(80,111,80),"max",List.of(115,111,115)):Map.of();event(e,Map.of("content",round==0?"开始读取。":"读取之后继续。","tool_calls",List.of(Map.of("index",0,"id","latency-"+round,"type","function","function",Map.of("name",tool,"arguments",JSON.writeValueAsString(args))))));}
        else {require(summaryEntered.await(10,TimeUnit.SECONDS),"SUMMARY_NOT_CONCURRENT");event(e,Map.of("content","主响应已经完成。"));}
        e.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }catch(Exception error){if(!(error instanceof java.io.IOException))failure=error;}finally{e.close();}}
    public static CompletableFuture<Map<String,Object>> run(UUID id){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!System.getProperty("mineagent.skillSmokeMode","").equals("response_latency")||result!=null)throw new IllegalStateException("SMOKE_DISABLED");
        result=new CompletableFuture<>();agent=id;
        try{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());http.createContext("/v1/chat/completions",ResponseLatencySmoke::request);http.start();}catch(Exception e){result.completeExceptionally(e);return result;}
        server(p->{var s=p.level().getServer();s.getPlayerList().op(p.nameAndId());var config=MineAgentRuntimeServices.config(s);require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","latency-fixture","provider.openai.enabled","true","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted(),"FIXTURE_CONFIG");for(int x=5;x<=7;x++)for(int z=5;z<=7;z++)p.level().getChunk(x,z);for(var pos:BlockPos.betweenClosed(new BlockPos(80,111,80),new BlockPos(115,111,115)))p.level().setBlock(pos,Blocks.STONE.defaultBlockState(),2);return true;})
            .thenCompose(v->models())
            .thenCompose(v->command("execute as @e[tag=divzero_missing_fixture] run say unreachable"))
            .thenCompose(v->command("execute if entity @e[tag=divzero_missing_fixture] run say unreachable"))
            .thenCompose(v->command("execute if entity @s run time query daytime"))
            .thenCompose(v->tool("scan_blocks",Map.of("blocks",List.of("minecraft:stone"),"min",List.of(80,111,80),"max",List.of(115,111,115))))
            .thenCompose(v->{require(((List<?>)v.get("found")).size()==1024&&((Number)v.get("nextOffset")).intValue()==1024,"SCAN_DEFAULT_PAGE");evidence.add(Map.of("scanHits",1024));return tool("scan_blocks",Map.of("blocks",List.of("minecraft:stone"),"min",List.of(80,111,80),"max",List.of(115,111,115),"offset",1024));})
            .thenCompose(v->{require(((List<?>)v.get("found")).size()==272&&((Number)v.get("nextOffset")).intValue()==-1,"SCAN_CONTINUATION");return tool("inspect_blocks",Map.of("min",List.of(80,110,80),"max",List.of(115,114,115)));})
            .thenCompose(v->{require(((Number)v.get("representedCells")).intValue()==6480,"INSPECT_PAGE");return tool("plan_world_geometry",Map.of("source","{\"origin\":[80,112,80],\"parts\":[{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[1,1,1],\"holes\":[],\"material\":\"minecraft:stone\"}]}"));})
            .thenCompose(v->{require("/parts/0/holes".equals(v.get("field")),"GEOMETRY_FIELD_LOCATION_"+v);evidence.add(v);return collision();})
            .thenCompose(v->{require(v.containsKey("position")&&v.containsKey("entities"),"COLLISION_LOCATION_"+v);evidence.add(v);return server(p->{try{var conversations=ServerConversations.get(p.level().getServer());conversation=conversations.store().nativeConversation(p.getUUID(),agent).conversationId();conversations.submitNative(p,agent,"请扫描方块并完成响应验证",true,UUID.randomUUID());chatStarted=true;return true;}catch(Exception e){throw new CompletionException(e);}});})
            .whenComplete((v,e)->{if(e!=null)failure=e;});return result;
    }
    private static CompletableFuture<Boolean> models(){return server(p->{try{
        var server=p.level().getServer();UUID second=MineAgentRuntimeServices.bodies(server).createPersistentAt("模型路由验证",p.getUUID(),p.level(),p.position().add(4,0,0)).agentId();String base="http://127.0.0.1:"+http.getAddress().getPort()+"/v1/";
        ServerAgentModels.save(p,Map.of("agentId",agent.toString(),"expectedRevision","0","mode","CUSTOM","model","private/alpha-fixture","baseUrl",base));
        ServerAgentModels.save(p,Map.of("agentId",second.toString(),"expectedRevision","0","mode","CUSTOM","model","private/beta-fixture","baseUrl",base));
        var worker=MineAgentRuntimeServices.worker(server);var config=MineAgentRuntimeServices.config(server);var world=MineAgentRuntimeServices.worldId(server);
        var first=worker.streamConversation(config,"SEMANTIC","model-routing-fixture",delta->{},()->true,null,UUID.randomUUID(),null,world,agent);
        var other=worker.streamConversation(config,"SEMANTIC","model-routing-fixture",delta->{},()->true,null,UUID.randomUUID(),null,world,second);
        return first.thenCombine(other,(a,b)->{require("private/alpha-fixture".equals(a.payload().get("requestedModel"))&&"private/beta-fixture".equals(b.payload().get("requestedModel")),"AI_MODEL_ROUTE_CROSSED");evidence.add(Map.of("separateAiModels",List.of(a.payload().get("requestedModel"),b.payload().get("requestedModel")),"unlistedIdsSavedThroughServerPanelRoute",true));return true;});
    }catch(Exception error){throw new CompletionException(error);}}).thenCompose(f->f);}
    private static CompletableFuture<Map<String,Object>> collision(){return server(p->{try{var pos=p.blockPosition();String source=JSON.writeValueAsString(Map.of("origin",List.of(pos.getX(),pos.getY(),pos.getZ()),"parts",List.of(Map.of("kind","box","min",List.of(0,0,0),"max",List.of(0,1,0),"material","minecraft:stone"))));return ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"plan_world_geometry",JSON.writeValueAsString(Map.of("source",source)),()->true);}catch(Exception error){throw new CompletionException(error);}}).thenCompose(f->f);}
    private static CompletableFuture<Map<String,Object>> command(String command){long start=System.nanoTime();return tool("run_game_command",Map.of("command",command)).thenApply(receipt->{long ms=(System.nanoTime()-start)/1000000;require(ms<2000&&!"UNKNOWN".equals(receipt.get("status")),"EMPTY_COMMAND_WAIT_"+receipt);evidence.add(Map.of("command",command,"millis",ms,"receipt",receipt));return receipt;});}
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(result==null||result.isDone())return;
        if(failure!=null||++ticks>1800){release.countDown();http.stop(0);result.completeExceptionally(new IllegalStateException("LATENCY_FIXTURE_"+Objects.toString(failure,"timeout"),failure));return;}
        if(!chatStarted||polling||ticks%5!=0)return;polling=true;
        server(p->{try{var conversations=ServerConversations.get(p.level().getServer());var store=conversations.store();var context=store.context(p.getUUID(),agent,conversation,null).orElseThrow();require(!Set.of("FAILED","CANCELLED","INTERRUPTED").contains(context.requestState()),"MAIN_REPLY_FAILED_"+context.errorCode());if(!context.requestState().equals("COMPLETE")||summaries.get()==0)return false;
            require(release.getCount()==1,"SUMMARY_NOT_BLOCKED");evidence.add(Map.of("phase",phase,"mainCompletedWhileSummaryBlocked",true,"mainRounds",rounds.get(),"summaries",summaries.get()));release.countDown();
            if(phase==0){phase=1;rounds.set(0);summaries.set(0);release=new CountDownLatch(1);summaryEntered=new CountDownLatch(1);for(int i=0;i<24;i++){var turn=store.begin(p.getUUID(),agent,conversation,UUID.randomUUID(),1,"Old user "+i,0);store.finish(turn.operationId(),"COMPLETE","Old assistant "+i+" "+"n".repeat(1600),"");}conversations.submitNative(p,agent,"history-fixture: 请直接回答",true,UUID.randomUUID());return false;}return true;
        }catch(Exception e){throw new CompletionException(e);}}).whenComplete((complete,error)->{polling=false;if(error!=null){failure=error;return;}if(complete){http.stop(0);result.complete(Map.of("status","PASS","provider","CONTROLLED_LOCAL_HTTP_NOT_MODEL","paidModelCalls",0,"evidence",evidence));}});
    }
    private ResponseLatencySmoke(){}
}
