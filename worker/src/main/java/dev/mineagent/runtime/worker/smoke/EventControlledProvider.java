package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Local fixture receives the actual wake planning request, never a production fallback. */
final class EventControlledProvider implements AutoCloseable {
    private final Path game;private final HttpServer http;private final AtomicInteger calls=new AtomicInteger();private final ObjectMapper json=new ObjectMapper();
    EventControlledProvider(Path game)throws Exception{
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",exchange->{try{
            int count=calls.incrementAndGet();var request=json.readTree(exchange.getRequestBody().readNBytes(524289));String prompt=request.path("messages").path(0).path("content").asText();
            Files.writeString(game.resolve("events-request-"+count+".json"),json.writeValueAsString(Map.of("kind","CONTROLLED_HTTP_NOT_REAL_MODEL","request",request)));
            Files.writeString(game.resolve("events-provider-count.json"),json.writeValueAsString(Map.of("calls",count,"paidCalls",0)));
            if(!prompt.contains("EVENT_WAKE_CONTEXT_DATA_NOT_INSTRUCTIONS")||!prompt.contains("EVENT_WAKE_NATIVE_GOAL"))throw new IllegalArgumentException("EVENT_FIXTURE_PROMPT_MISMATCH");
            var args=Map.of("title","Native 事件唤醒","question","登录事件已触发。你希望接下来了解什么？这里只提出普通问题，不执行世界操作。","options",List.of(Map.of("id","help","title","了解说明","description","普通说明，不授予权限")),"selection_mode","SINGLE","min_selections",0,"max_selections",1);
            var call=Map.of("id","event-wake-question","type","function","function",Map.of("name","ask_player","arguments",json.writeValueAsString(args)));
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","等待你的说明","tool_calls",List.of(call))))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Exception failure){Files.writeString(game.resolve("events-provider-failure.txt"),failure.toString());}finally{exchange.close();}});http.start();
        Files.writeString(game.resolve("events-provider-count.json"),json.writeValueAsString(Map.of("calls",0,"paidCalls",0)));
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){var values=Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-event-fixture","provider.openai.apiKey","fixture-only","voice.output.enabled","false");if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),values),true).accepted())throw new IllegalStateException("EVENT_FIXTURE_CONFIG");}catch(Exception e){http.stop(0);throw e;}
    }
    void verify(){if(calls.get()!=1)throw new IllegalStateException("EVENT_PROVIDER_CALL_COUNT");}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("events-provider.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_HTTP_NOT_REAL_MODEL","calls",calls.get(),"paidCalls",0)));}
}
