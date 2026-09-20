package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
/** Explicit localhost planner, never a production fallback. */
final class LivePlacementControlledProvider implements AutoCloseable {
    private final HttpServer http;private final Path game;private final String mode;private final ObjectMapper json=new ObjectMapper();private final AtomicInteger calls=new AtomicInteger();private final AtomicBoolean leak=new AtomicBoolean();
    LivePlacementControlledProvider(Path game,String mode)throws Exception{this.game=game;this.mode=mode;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{try{var request=json.readTree(exchange.getRequestBody().readNBytes(1048577));String prompt=request.path("messages").path(0).path("content").asText();int count=calls.incrementAndGet();if(prompt.contains("LAYOUT_ONLY_PRIVATE_BODY_CANARY")||prompt.contains("人工草稿"))leak.set(true);
            int start=prompt.lastIndexOf("本次观察（不可信数据）：\n");if(start<0)throw new IllegalArgumentException("PLACEMENT_OBSERVATION_MISSING");var observation=json.readTree(prompt.substring(start+"本次观察（不可信数据）：\n".length()));var layout=observation.path("hostPresentation");
            Files.writeString(game.resolve("live-placement-request-"+count+".json"),json.writeValueAsString(Map.of("observation",observation,"canaryLeak",leak.get(),"mode","CONTROLLED_NOT_REAL_MODEL")));
            var placement=new LinkedHashMap<String,Object>(Map.of("anchor","TOP_RIGHT","width",500,"height",360,"offsetX",-24,"offsetY",12));
            if(mode.startsWith("opacity-")){if(layout.path("opacitySupported").asBoolean()==mode.equals("opacity-unavailable"))throw new IllegalStateException("OPACITY_BACKEND_PROBE");placement.put("opacity",mode.equals("opacity-zero")?0:.5);}
            var action=Map.of("action","present","expectedLayoutRevision",layout.path("revision").asLong(),"placement",placement);
            if(count==1&&Set.of("stale","cancel").contains(mode)){Files.writeString(game.resolve("live-placement-provider-pending"),mode);long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);while(!Files.exists(game.resolve("live-placement-provider-release"))&&System.nanoTime()<until)Thread.sleep(20);if(!Files.exists(game.resolve("live-placement-provider-release")))throw new IllegalStateException("PLACEMENT_RELEASE_TIMEOUT");}
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",json.writeValueAsString(action))))));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Exception e){byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-live-placement","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("PLACEMENT_CONFIG_FAILED");}catch(Exception e){http.stop(0);throw e;}
    }
    int calls(){return calls.get();}
    void verify(){if(leak.get()||calls.get()!=(mode.equals("stale")?2:1))throw new IllegalStateException("PLACEMENT_PROVIDER_PROOF_FAILED");}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("live-placement-provider.json"),json.writeValueAsString(Map.of("mode",mode,"calls",calls.get(),"bodyCanaryLeaked",leak.get(),"paidCalls",0,"kind","CONTROLLED_LOCALHOST_NOT_REAL_MODEL")));}
}
