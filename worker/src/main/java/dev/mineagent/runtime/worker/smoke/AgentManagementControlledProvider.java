package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local, explicit fixture responses exercise dispatch/Native effects, not model planning quality. */
final class AgentManagementControlledProvider implements AutoCloseable {
    private final HttpServer http;private final Path game;private final AtomicInteger calls=new AtomicInteger();private final ObjectMapper json=new ObjectMapper();
    AgentManagementControlledProvider(Path game)throws Exception{
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{try{
            String request=new String(exchange.getRequestBody().readNBytes(262145),java.nio.charset.StandardCharsets.UTF_8);if(request.length()>262144)throw new IllegalArgumentException("FIXTURE_BODY_BUDGET");int count=calls.incrementAndGet();
            Files.writeString(game.resolve("agent-management-provider-"+count+".json"),request);Files.writeString(game.resolve("agent-management-provider-count.json"),json.writeValueAsString(Map.of("calls",count,"paidCalls",0)));
            Object args=count==1?Map.of("x",930,"y",170,"z",5):Map.of("checks",List.of(Map.of("kind","position","x",930,"y",170,"z",5),Map.of("kind","block","x",924,"y",170,"z",2,"block","minecraft:obsidian")));
            var call=Map.of("id","fresh-plan-"+count,"type","function","function",Map.of("name",count==1?"move_to":"finish_task","arguments",json.writeValueAsString(args)));
            byte[] bytes=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","","tool_calls",List.of(call))))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){Files.writeString(game.resolve("agent-management-provider-error.txt"),failure.toString());}finally{exchange.close();}});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-agent-management","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("MANAGEMENT_FIXTURE_CONFIG");}
    }
    void verify(){if(calls.get()!=2)throw new IllegalStateException("MANAGEMENT_PLANNER_CALL_COUNT");}
    @Override public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("agent-management-provider.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_HTTP_NOT_REAL_MODEL","calls",calls.get(),"paidCalls",0)));}
}
