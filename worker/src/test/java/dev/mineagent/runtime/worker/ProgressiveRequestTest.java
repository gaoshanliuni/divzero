package dev.mineagent.runtime.worker;

import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.core.conversation.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveRequestTest {
    @TempDir java.nio.file.Path root;
    @Test void actualHttpRequestUsesSelectedSchemasStructuredRolesAndRequiredReasoning()throws Exception{
        var json=new ObjectMapper();var captured=new CopyOnWriteArrayList<JsonNode>();var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{captured.add(json.readTree(exchange.getRequestBody()));byte[] body=("data: {\"model\":\"fixture\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(root.resolve("runtime.db"));var worker=new WorkerRequestHandler()){
            assertEquals("storage.configured",worker.handle(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",root.resolve("content").toString()))).type());
            worker.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind","openai-compatible","baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","model","fixture","apiKey","fixture")));
            var messages=List.of(Map.<String,Object>of("role","system","content","STABLE_APP_RULES"),Map.<String,Object>of("role","user","content","<data_context source=\"memory\">historical data</data_context>"),Map.<String,Object>of("role","user","content","你好"));
            var payload=new LinkedHashMap<String,Object>(Map.of("capability","SEMANTIC","prompt","LEGACY_ENVELOPE_NOT_FOR_PROVIDER","conversationTools",true,"conversationMessages",messages,"toolHistory",List.of()));
            assertEquals("model.stream.result",worker.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",payload),delta->{}).type());
            assertEquals(CapabilityCatalog.RESIDENT,names(captured.getFirst()));assertEquals("system",captured.getFirst().path("messages").get(0).path("role").asText());
            assertFalse(captured.getFirst().toString().contains("LEGACY_ENVELOPE_NOT_FOR_PROVIDER"));assertFalse(captured.getFirst().toString().contains("plan_building"));
            var scope=new CapabilitySession();scope.load("building");scope.load("ui");payload.put("toolNames",scope.tools());payload.put("loadedSkills",scope.groups());
            payload.put("toolHistory",List.of(Map.of("role","assistant","content","","reasoning_content","required_provider_reasoning","tool_calls",List.of(Map.of("id","call_a","type","function","function",Map.of("name","inspect_buildings","arguments","{}")))),Map.of("role","tool","tool_call_id","call_a","content","{\"status\":\"UNKNOWN\",\"operation\":\"op1\"}")));
            assertEquals("model.stream.result",worker.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",payload),delta->{}).type());
            var expanded=captured.get(1);assertTrue(names(expanded).containsAll(List.of("plan_building","set_native_ui","stop_actions")));assertFalse(names(expanded).contains("python_execute"));
            assertEquals(CapabilityCatalog.RESIDENT,names(expanded).subList(0,CapabilityCatalog.RESIDENT.size()));
            assertTrue(expanded.path("messages").toString().contains("<skill_content name=\\\"building\\\">"));assertFalse(expanded.path("messages").toString().contains("专用 Python"));
            assertTrue(expanded.path("messages").toString().contains("required_provider_reasoning"));assertTrue(expanded.path("messages").toString().contains("UNKNOWN"));
            payload.put("toolNames",List.of("not_a_shipped_tool"));assertEquals("error",worker.handleStreaming(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",payload),delta->{}).type());assertEquals(2,captured.size());
        }finally{http.stop(0);}
    }
    @Test void legacyTaskPlannerAlsoDisclosesOnDemandInRealHttpBodies()throws Exception{
        var json=new ObjectMapper();var captured=new CopyOnWriteArrayList<JsonNode>();var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{captured.add(json.readTree(exchange.getRequestBody()));String name=captured.size()==1?"skill":"move_to",args=captured.size()==1?"{\"name\":\"physical\"}":"{\"x\":1,\"y\":64,\"z\":2}";byte[] body=json.writeValueAsBytes(Map.of("model","fixture","choices",List.of(Map.of("message",Map.of("role","assistant","content","","reasoning_content","opaque_required","tool_calls",List.of(Map.of("id","c"+captured.size(),"type","function","function",Map.of("name",name,"arguments",args))))))));exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(root.resolve("runtime.db"));var worker=new WorkerRequestHandler()){
            worker.handle(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",root.resolve("plan-content").toString())));
            worker.handle(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind","openai-compatible","baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","model","fixture","apiKey","fixture")));
            UUID world=UUID.randomUUID(),agent=UUID.randomUUID();dev.mineagent.runtime.api.task.ManagedTask task;
            try(var tasks=dev.mineagent.runtime.core.task.TaskManager.open(root.resolve("runtime.db"),world,java.time.Clock.systemUTC())){task=tasks.create(agent,UUID.randomUUID(),"你好",50,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("plan",Set.of())));}
            var payload=Map.<String,Object>of("worldId",world.toString(),"agentId",agent.toString(),"taskId",task.taskId().toString(),"taskRevision",task.revision(),"budgetTaskRevisionKind","MUTATION","packageRevision",0,"prompt","你好","toolScope","GENERAL");
            var reply=worker.handle(new WorkerEnvelope(1,UUID.randomUUID(),"agent.plan",payload));assertEquals("agent.plan.result",reply.type(),reply.payload().toString());assertEquals(2,captured.size());
            assertFalse(names(captured.getFirst()).contains("move_to"));assertFalse(captured.getFirst().toString().contains("inspect_native_method_body"));assertEquals("system",captured.getFirst().path("messages").get(0).path("role").asText());
            assertTrue(names(captured.getLast()).contains("move_to"));assertFalse(names(captured.getLast()).contains("refresh_native_api"));assertTrue(captured.getLast().toString().contains("opaque_required"));
        }finally{http.stop(0);}
    }
    private static List<String> names(JsonNode request){var names=new ArrayList<String>();for(var tool:request.path("tools"))names.add(tool.path("function").path("name").asText());return names;}
}
