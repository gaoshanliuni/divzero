package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ServerConversations;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Deterministic localhost protocol fixture, not a paid model or model-quality evaluation. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ToolRepairSmoke {
    private static final ObjectMapper JSON=new ObjectMapper();private static final List<Object> evidence=new ArrayList<>();
    private static HttpServer http;private static CompletableFuture<Map<String,Object>> result;private static UUID agent,operation,conversation;
    private static volatile Throwable failure;private static int ticks;private static volatile int rounds;private static boolean busy;private static volatile List<String> users;private static volatile String retryBody;
    private static boolean gameContent(){return System.getProperty("mineagent.skillSmokeMode","").equals("game_content_route");}
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void require(boolean yes,String code){if(!yes)throw new IllegalStateException(code);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> work){var f=new CompletableFuture<T>();var server=mc().getSingleplayerServer();var owner=mc().player.getUUID();server.submit(()->work.apply(server.getPlayerList().getPlayer(owner))).whenComplete((v,e)->mc().execute(()->{if(e!=null)f.completeExceptionally(e);else f.complete(v);}));return f;}
    private static JsonNode receipt(JsonNode messages,String id)throws Exception{for(var m:messages)if(m.path("role").asText().equals("tool")&&m.path("tool_call_id").asText().equals(id))return JSON.readTree(m.path("content").asText());throw new IllegalStateException("TOOL_RECEIPT_MISSING_"+id);}
    private static Map<String,Object> call(String id,String tool,Object args){try{return Map.of("index",0,"id",id,"type","function","function",Map.of("name",tool,"arguments",args instanceof String raw?raw:JSON.writeValueAsString(args)));}catch(Exception e){throw new CompletionException(e);}}
    private static void event(com.sun.net.httpserver.HttpExchange exchange,Object delta)throws Exception{exchange.getResponseBody().write(("data: "+JSON.writeValueAsString(Map.of("model","controlled-tool-repair","choices",List.of(Map.of("delta",delta))))+"\n\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
    private static void request(com.sun.net.httpserver.HttpExchange exchange){try{
        var input=JSON.readTree(exchange.getRequestBody().readNBytes(4*1024*1024));require(exchange.getRequestURI().getPath().equals("/v1/chat/completions"),"FIXTURE_ENDPOINT");
        if(!input.path("stream").asBoolean()||!input.path("tools").isArray()||input.path("tools").isEmpty()){if(input.path("stream").asBoolean()){exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);event(exchange,Map.of("content","故障反馈验证"));exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));return;}byte[] body=JSON.writeValueAsBytes(Map.of("model","controlled-tool-repair","choices",List.of(Map.of("message",Map.of("content","故障反馈验证")))));exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;}
        if(gameContent()){gameRequest(exchange,input);return;}
        var messages=input.path("messages");var currentUsers=new ArrayList<String>();for(var m:messages)if(m.path("role").asText().equals("user"))currentUsers.add(m.path("content").asText());
        if(users==null)users=List.copyOf(currentUsers);else if(rounds==9){require(currentUsers.size()==users.size()+1&&currentUsers.subList(0,users.size()).equals(users),"EXTRA_USER_PROMPT_INJECTED_AFTER_FAILURE");var feedback=JSON.readTree(currentUsers.getLast());require(feedback.path("source").asText().equals("provider_response_failure")&&feedback.path("executionState").asText().equals("NO_TOOL_CALLS_DISPATCHED")&&!feedback.has("prompt"),"PARTIAL_FAILURE_NOT_PURE_DIAGNOSTICS");evidence.add(feedback);}else require(users.equals(currentUsers),"EXTRA_USER_PROMPT_INJECTED_AFTER_FAILURE");
        int round=rounds++;Map<String,Object> tool=null;
        switch(round){
            case 0->tool=call("bad_args","run_game_command",Map.of("command",List.of("setblock 3 101 3 minecraft:gold_block")));
            case 1->{var r=receipt(messages,"bad_args");require(r.path("executionState").asText().equals("NOT_STARTED")&&r.path("issues").toString().contains("command"),"PARAMETER_CAUSE_NOT_RETURNED");evidence.add(r);tool=call("native_bad","run_game_command",Map.of("command","setblock 3 101"));}
            case 2->{var r=receipt(messages,"native_bad");require(r.path("executionState").asText().equals("NOT_STARTED")&&r.path("field").asText().equals("command")&&r.has("cursor"),"NATIVE_COMMAND_CAUSE_NOT_RETURNED");evidence.add(r);tool=call("corrected","run_game_command",Map.of("command","setblock 3 101 3 minecraft:gold_block"));}
            case 3->{var r=receipt(messages,"corrected");require(r.path("status").asText().equals("APPLIED"),"CORRECTED_TOOL_DID_NOT_EXECUTE");tool=call("broken","apply_building",Map.of("id","broken_storage","revision",1));}
            case 4->{var r=receipt(messages,"broken");require(r.path("status").asText().equals("UNKNOWN")&&!r.path("diagnostic").asText().isBlank()&&r.path("causes").toString().contains("SQLiteException"),"ASYNC_ROOT_CAUSE_NOT_RETURNED");evidence.add(r);tool=call("duplicate","apply_building","{\"revision\":1,\"id\":\"broken_storage\"}");}
            case 5->{var r=receipt(messages,"duplicate");require(r.path("error").asText().equals("PREVIOUS_WRITE_OUTCOME_UNKNOWN")&&r.path("executionState").asText().equals("NOT_STARTED"),"UNKNOWN_WRITE_WAS_REPLAYED");evidence.add(r);tool=call("inspect","inspect_operations",Map.of("operation_id",receipt(messages,"broken").path("operation_id").asText()));}
            case 6->{var r=receipt(messages,"inspect");require(r.path("status").asText().equals("OBSERVED")&&r.path("text").asText().contains("SQLiteException"),"DURABLE_FAILURE_DIAGNOSTIC_MISSING");tool=call("wrong_name","MissingTool",Map.of());}
            case 7->{require(receipt(messages,"wrong_name").path("error").asText().equals("AGENT_TOOL_UNKNOWN"),"TOOL_NAME_FAILURE_ABORTED_TURN");retryBody=input.toString();byte[] body=JSON.writeValueAsBytes(Map.of("error",Map.of("code","busy","message","controlled temporary overload")));exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);evidence.add(Map.of("providerStatus",503,"preOutput",true));return;}
            case 8->{require(input.toString().equals(retryBody),"PROVIDER_RETRY_CHANGED_MESSAGES_OR_REPLAYED_TOOLS");exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);event(exchange,Map.of("content","连接中断前的内容。"));return;}
            case 9->{require(messages.toString().contains("连接中断前的内容。"),"PARTIAL_ASSISTANT_MISSING_ON_CONTINUATION");}
            default->throw new IllegalStateException("UNEXPECTED_PROVIDER_ROUND_"+round);
        }
        exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
        event(exchange,Map.of("content",round==0?"开始验证，已有输出保留。":""));
        if(tool!=null){event(exchange,Map.of("reasoning_content","受控协议字段，不代表真实模型推理"));event(exchange,Map.of("tool_calls",List.of(tool)));}
        else event(exchange,Map.of("content","已收到具体错误，未重放未知写入。验证结束。"));
        exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }catch(Throwable error){failure=error;try{Files.writeString(mc().gameDirectory.toPath().resolve("tool-repair-provider-failure.txt"),error.toString());}catch(Exception ignored){}}finally{exchange.close();}}
    private static void gameRequest(com.sun.net.httpserver.HttpExchange exchange,JsonNode input)throws Exception{
        var messages=input.path("messages");var currentUsers=new ArrayList<String>();for(var m:messages)if(m.path("role").asText().equals("user"))currentUsers.add(m.path("content").asText());
        if(users==null)users=List.copyOf(currentUsers);else require(users.equals(currentUsers),"GAME_ROUTE_ADDED_USER_PROMPT");
        var names=new HashSet<String>();for(var tool:input.path("tools"))names.add(tool.path("function").path("name").asText());
        require(names.containsAll(List.of("generate_content_package","inspect_modeling","validate_model_geometry"))&&!names.contains("python_execute"),"GAME_ROUTE_WRONG_ADVERTISED_TOOLS");
        int round=rounds++;Map<String,Object> tool=null;
        switch(round){
            case 0->tool=call("host_skill","skill",Map.of("name","host"));
            case 1->{var r=receipt(messages,"host_skill");require(r.path("error").asText().equals("GAME_CONTENT_TOOL_REQUIRED"),"WRONG_HOST_SKILL_LOADED");evidence.add(r);tool=call("wrong_python","python_execute",Map.of("purpose","制作召唤权杖","script","print('should never run')","timeout_seconds",30));}
            case 2->{var r=receipt(messages,"wrong_python");require(r.path("error").asText().equals("GAME_CONTENT_TOOL_REQUIRED")&&r.path("executionState").asText().equals("NOT_STARTED"),"PYTHON_WAS_DISPATCHED_FOR_GAME_ITEM");evidence.add(r);tool=call("model_contract","inspect_modeling",Map.of());}
            case 3->{require(receipt(messages,"model_contract").has("primitives"),"NATIVE_MODEL_CONTRACT_NOT_READ");var model=Map.of("version",1,"collision",List.of(-.1,0,-.1,.1,.9,.1),"boxes",List.of(Map.of("from",List.of(-.05,0,-.05),"to",List.of(.05,.8,.05),"color","#8B5A2B")));tool=call("model_check","validate_model_geometry",Map.of("source",JSON.writeValueAsString(model),"target","item"));}
            case 4->{var r=receipt(messages,"model_check");require(r.path("status").asText().equals("GEOMETRY_VALIDATED_NOT_EXECUTED"),"NATIVE_MODEL_CHECK_FAILED_"+r);evidence.add(r);}
            default->throw new IllegalStateException("GAME_ROUTE_EXTRA_ROUND");
        }
        exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
        if(round==0)event(exchange,Map.of("content","开始创建召唤权杖。"));
        if(tool!=null)event(exchange,Map.of("tool_calls",List.of(tool)));else event(exchange,Map.of("content","已切回游戏内容工具并验证模型。"));
        exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }
    public static CompletableFuture<Map<String,Object>> run(UUID id){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!Set.of("tool_repair","game_content_route").contains(System.getProperty("mineagent.skillSmokeMode",""))||result!=null)throw new IllegalStateException("SMOKE_DISABLED");
        result=new CompletableFuture<>();agent=id;operation=UUID.randomUUID();
        try{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",ToolRepairSmoke::request);http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());http.start();}
        catch(Exception e){result.completeExceptionally(e);return result;}
        server(p->{try{
            var s=p.level().getServer();var cfg=MineAgentRuntimeServices.config(s);require(cfg.apply(new dev.mineagent.runtime.api.config.ConfigPatch(cfg.snapshot().revision(),Map.of("provider.openai.enabled","true","provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-tool-repair","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted(),"FIXTURE_CONFIG_REJECTED");
            var scope=new dev.mineagent.runtime.core.building.ConstructionCatalog.Scope(MineAgentRuntimeServices.worldId(s),p.getUUID(),agent);Files.createDirectories(s.getServerDirectory().resolve("mineagent-runtime-data/buildings").resolve(dev.mineagent.runtime.core.building.ConstructionCatalog.filename(scope,"broken_storage")));
            var conversations=ServerConversations.get(s);conversation=conversations.store().nativeConversation(p.getUUID(),agent).conversationId();conversations.submitNative(p,agent,gameContent()?"做一个召唤权杖":"请进行建筑工具错误反馈验证，保留已完成部分。",true,operation);return true;
        }catch(Exception e){throw new CompletionException(e);}}).whenComplete((v,e)->{if(e!=null)failure=e;});return result;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(result==null||result.isDone())return;ticks++;if(failure!=null||ticks>1600){http.stop(0);result.completeExceptionally(new IllegalStateException("TOOL_REPAIR_"+rounds+": "+Objects.toString(failure,"timeout"),failure));return;}if(busy||conversation==null||ticks%10!=0)return;busy=true;
        server(p->{try{var store=ServerConversations.get(p.level().getServer()).store();var usage=store.context(p.getUUID(),agent,conversation,null).orElseThrow();require(!Set.of("FAILED","CANCELLED","INTERRUPTED").contains(usage.requestState()),"CONVERSATION_INTERRUPTED_"+usage.errorCode());if(!usage.requestState().equals("COMPLETE"))return false;
            var snapshot=store.nativeSnapshot(p.getUUID(),agent,conversation,usage.assistantMessageId(),0,0);String text=snapshot.body().text();if(gameContent()){
                require(text.contains("开始创建召唤权杖。")&&text.contains("已切回游戏内容工具并验证模型。")&&rounds==5,"GAME_ROUTE_DID_NOT_CONTINUE");
                require(dev.mineagent.runtime.neoforge.hostsupport.HostToolBridge.pending().isEmpty(),"UNNECESSARY_HOST_APPROVAL_SHOWN");
                require(usage.operationId().equals(operation)&&store.get(p.getUUID(),agent,conversation).messageCount()==2,"GAME_ROUTE_CREATED_NEW_TURN");
                evidence.add(Map.of("requestState",usage.requestState(),"sameOperation",true,"nativeModelContractAndCheck",true,"hostRequestDispatched",false));return true;
            }require(text.contains("开始验证，已有输出保留。")&&text.contains("验证结束。")&&text.contains("连接中断前的内容。"),"STREAM_OUTPUT_WAS_REPLACED");require(p.level().getBlockState(new net.minecraft.core.BlockPos(3,101,3)).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK),"CORRECTED_WORLD_RESULT_MISSING_"+p.level().getBlockState(new net.minecraft.core.BlockPos(3,101,3)));require(rounds==10,"MODEL_ROUND_COUNT_"+rounds);require(usage.operationId().equals(operation)&&store.get(p.getUUID(),agent,conversation).messageCount()==2,"RECOVERY_CREATED_ANOTHER_USER_TURN");evidence.add(Map.of("requestState",usage.requestState(),"sameOperation",usage.operationId().equals(operation),"correctedWorldWrite",true,"streamPrefixAndSuffixRetained",true));return true;
        }catch(Exception e){throw new CompletionException(e);}}).whenComplete((complete,error)->{busy=false;if(error!=null){failure=error;return;}if(complete){http.stop(0);result.complete(Map.of("status","PASS","provider","CONTROLLED_LOCAL_HTTP_NOT_MODEL","paidModelCalls",0,"streamRequests",rounds,"extraUserPrompts",0,"providerFailureObservations",gameContent()?0:1,"evidence",evidence));}});
    }
    private ToolRepairSmoke(){}
}
