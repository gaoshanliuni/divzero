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
    private static List<String> names(JsonNode request){var names=new ArrayList<String>();for(var tool:request.path("tools"))names.add(tool.path("function").path("name").asText());return names;}
}
