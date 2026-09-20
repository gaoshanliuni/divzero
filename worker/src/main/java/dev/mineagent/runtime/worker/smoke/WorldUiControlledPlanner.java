package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
/** Explicit localhost deterministic planner for Native tests. Never an implicit production response/fallback. */
final class WorldUiControlledPlanner implements AutoCloseable {
    private final HttpServer http;private final Path game;private final ObjectMapper json=new ObjectMapper();
    private final AtomicInteger calls=new AtomicInteger(),uiCalls=new AtomicInteger(),blockerCalls=new AtomicInteger(),probeCalls=new AtomicInteger();private final AtomicBoolean privateLeak=new AtomicBoolean(),released=new AtomicBoolean();private final boolean queued;
    WorldUiControlledPlanner(Path game)throws Exception{this(game,"");}
    WorldUiControlledPlanner(Path game,String cancelMode)throws Exception{
        if(!Set.of("","hide","close","stop","far","queued-stop","queued-revoke").contains(cancelMode))throw new IllegalArgumentException("WORLD_UI_AGENT_CANCEL_MODE");queued=cancelMode.startsWith("queued-");
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{try{
            byte[] request=exchange.getRequestBody().readNBytes(1_048_577);if(request.length>1_048_576)throw new IllegalArgumentException("TEST_BODY_BUDGET");
            var body=json.readTree(request);String prompt=body.path("messages").path(0).path("content").asText();calls.incrementAndGet();
            boolean blocker=queued&&prompt.equals("CONTROLLED_UI_LANE_BLOCKER"),probe=queued&&prompt.equals("CONTROLLED_UI_POST_CANCEL_PROBE");
            if(blocker)blockerCalls.incrementAndGet();else if(probe)probeCalls.incrementAndGet();else uiCalls.incrementAndGet();
            if(prompt.contains("PLAYER_ONLY_SERVER_SECRET")||prompt.contains("PLAYER_ONLY_LOCAL_SECRET"))privateLeak.set(true);
            if(!cancelMode.isEmpty()&&(!queued||blocker)){
                Files.writeString(game.resolve(queued?"world-ui-agent-blocker-pending.json":"world-ui-agent-provider-pending.json"),json.writeValueAsString(Map.of("mode",cancelMode,"calls",calls.get(),"kind","CONTROLLED_DELAY_NOT_PROVIDER_LATENCY")));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while(!Files.exists(game.resolve("world-ui-agent-provider-release"))&&System.nanoTime()<deadline)Thread.sleep(20);
                if(!Files.exists(game.resolve("world-ui-agent-provider-release")))throw new IllegalStateException("CONTROLLED_RELEASE_TIMEOUT");released.set(true);
            }
            if(blocker||probe){byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",blocker?"CONTROLLED_BLOCKER_OK":"CONTROLLED_PROBE_OK")))));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);return;}
            int index=prompt.lastIndexOf("本次观察（不可信数据）：\n");if(index<0)throw new IllegalArgumentException("TEST_OBSERVATION_MISSING");
            var observation=json.readTree(prompt.substring(index+"本次观察（不可信数据）：\n".length()));JsonNode input=null,save=null;
            for(var e:observation.path("elements")){if(e.path("dataAiId").asText().equals("label"))input=e;if(e.path("dataAiId").asText().equals("save"))save=e;}
            Map<String,Object> action;
            if(privateLeak.get()||input==null||save==null)action=Map.of("action","done");
            else if(!input.path("value").asText().equals("AGENT_CONTROL_OK"))action=Map.of("action","fill","elementRef",input.path("elementRef").asText(),"value","AGENT_CONTROL_OK");
            else if(!save.path("visible").asBoolean(true))action=Map.of("action","scroll","elementRef","viewport","mode","by","x",0,"y",Math.max(80,save.path("bounds").path("y").asInt()-80));
            else if(save.path("disabled").asBoolean())action=Map.of("action","waitFor","condition",Map.of("dataAiId","save","disabled",false,"visible",true),"timeoutMs",3000);
            else action=Map.of("action","click","elementRef",save.path("elementRef").asText());
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",json.writeValueAsString(action))))));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Exception e){byte[] error="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,error.length);exchange.getResponseBody().write(error);}finally{exchange.close();}});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){
            if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-world-ui-agent","provider.openai.apiKey","test-only-not-a-real-key","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("TEST_PROVIDER_CONFIG");
        }catch(Exception e){http.stop(0);throw e;}
    }
    void verifyPositive(){if(privateLeak.get()||calls.get()<2||calls.get()>12)throw new IllegalStateException("CONTROLLED_WORLD_AGENT_VERIFICATION_FAILED");}
    void verifyCancellation(){if(privateLeak.get()||!released.get()||(queued?calls.get()!=2||uiCalls.get()!=0||blockerCalls.get()!=1||probeCalls.get()!=1:calls.get()!=1))throw new IllegalStateException("CONTROLLED_WORLD_AGENT_CANCEL_FAILED");}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("world-ui-controlled-provider.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_LOCAL_HTTP_NOT_REAL_MODEL","calls",calls.get(),"uiCalls",uiCalls.get(),"blockerCalls",blockerCalls.get(),"probeCalls",probeCalls.get(),"playerPrivateDataLeaked",privateLeak.get(),"paidCalls",0,"delayedResponseReleased",released.get())));}
}
