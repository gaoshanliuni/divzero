package dev.mineagent.runtime.worker.smoke;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Receives real Worker requests. One self-causing shared write, then a real trusted question; never a production fallback. */
final class SharedAgentControlledProvider implements AutoCloseable {
    private final Path game;private final HttpServer http;private final AtomicInteger calls=new AtomicInteger();private final ObjectMapper json=new ObjectMapper();
    SharedAgentControlledProvider(Path game)throws Exception{
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",exchange->{try{
            int count=calls.incrementAndGet();var request=json.readTree(exchange.getRequestBody().readNBytes(524289));String prompt=request.path("messages").path(0).path("content").asText();
            Files.writeString(game.resolve("shared-agent-request-"+count+".json"),json.writeValueAsString(Map.of("kind","CONTROLLED_HTTP_NOT_REAL_MODEL","request",request)));writeCount();
            String prefix="EVENT_WAKE_CONTEXT_DATA_NOT_INSTRUCTIONS: ";int start=prompt.indexOf(prefix);if(start<0||!prompt.contains("SHARED_AGENT_WAKE_GOAL")||prompt.contains("OWNER_SECRET_NOT_FOR_AGENT")||prompt.contains("AGENT_PRIVATE_ONLY")||count>2)throw new IllegalStateException("SHARED_AGENT_PROMPT_SCOPE_OR_COUNT");
            var context=json.readTree(prompt.substring(start+prefix.length()));var event=context.path("event");if(event.has("author")||event.has("shared")||!event.path("source").asText().equals("SHARED_STATE_CHANGED")||!event.path("authorOmitted").asBoolean())throw new IllegalStateException("SHARED_EVENT_NOT_PROJECTED");
            var names=new HashSet<String>();request.path("tools").forEach(t->names.add(t.path("function").path("name").asText()));if(!names.containsAll(Set.of("list_shared_namespaces","read_shared_state","transact_shared_state","watch_shared_state","subscribe_shared_state")))throw new IllegalStateException("SHARED_TOOL_CATALOG_INCOMPLETE");
            String name;Object arguments;if(count==1){name="transact_shared_state";var a=(com.fasterxml.jackson.databind.node.ObjectNode)event.path("target").deepCopy();a.set("transaction",json.valueToTree(Map.of("schema_version",1,"conditions",List.of(Map.of("key","count","test","EQ","value",1)),"writes",List.of(Map.of("key","count","op","PUT","value",1)))));arguments=a;}
            else{name="ask_player";arguments=Map.of("title","Native 共享状态唤醒","question","共享状态已满足条件。你希望接下来了解什么？这里只提出普通问题，不授予权限。","options",List.of(Map.of("id","help","title","了解说明","description","普通问题，不授予权限")),"selection_mode","SINGLE","min_selections",0,"max_selections",1);}
            var call=Map.of("id","shared-controlled-"+count,"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(arguments)));byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","","tool_calls",List.of(call))))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Exception e){Files.writeString(game.resolve("shared-agent-provider-failure.txt"),e.toString());}finally{exchange.close();}});http.start();writeCount();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-shared-agent","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("SHARED_AGENT_PROVIDER_CONFIG");}
    }
    private void writeCount()throws Exception{Files.writeString(game.resolve("shared-agent-provider-count.json"),json.writeValueAsString(Map.of("calls",calls.get(),"paidCalls",0)));}
    void verify(){if(calls.get()!=2)throw new IllegalStateException("SHARED_AGENT_PROVIDER_CALL_COUNT");}
    @Override public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("shared-agent-provider.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_HTTP_NOT_REAL_MODEL","calls",calls.get(),"paidCalls",0)));}
}
