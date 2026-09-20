package dev.mineagent.runtime.worker.smoke;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.PlayerControlPlan;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Local model transport only; actual native gameplay effects are checked separately. */
final class PlayerBodyControlledProvider implements AutoCloseable {
    private final HttpServer http;private final Path game;private final AtomicInteger calls=new AtomicInteger();
    PlayerBodyControlledProvider(Path game)throws Exception{this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",e->{try{
        int n=calls.incrementAndGet();String input=new String(e.getRequestBody().readNBytes(128*1024),java.nio.charset.StandardCharsets.UTF_8);if(n>6||!input.contains("BODY_CONTROL_FIXTURE_"+n))throw new IllegalArgumentException("BODY_PROVIDER_REQUEST");
        var steps=n==1?List.of(step("HOTBAR",1,0,0,0),step("LOOK",4,0,45,0),step("USE",1,0,0,0),step("WAIT",8,0,0,0),step("HOTBAR",1,0,0,1),step("ATTACK",45,0,0,0),step("LOOK",4,90,-45,0),step("JUMP",1,0,0,0),step("SPRINT_FORWARD",20,0,0,0),step("WAIT",5,0,0,0),step("SNEAK",3,0,0,0),step("RIGHT",3,0,0,0),step("BACK",3,0,0,0),step("LEFT",3,0,0,0),step("HOTBAR",1,0,0,2)):List.of(step("FORWARD",100,0,0,0),step("FORWARD",100,0,0,0));
        var json=new ObjectMapper();byte[] output=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",json.writeValueAsString(new PlayerControlPlan(steps)))))));e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);
    }catch(Exception error){Files.writeString(game.resolve("player-body-provider-failure.txt"),error.toString());}finally{e.close();}});http.start();
        try(var cfg=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!cfg.apply(new dev.mineagent.runtime.api.config.ConfigPatch(cfg.snapshot().revision(),Map.of("provider.openai.enabled","true","provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","local-player-body","provider.openai.apiKey","fixture-only","runtime.initialized","true","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("BODY_PROVIDER_CONFIG");}}
    private static PlayerControlPlan.Step step(String action,int ticks,double yaw,double pitch,int slot){return new PlayerControlPlan.Step(action,ticks,yaw,pitch,slot);}
    void verify(){if(calls.get()!=6||Files.exists(game.resolve("player-body-provider-failure.txt")))throw new IllegalStateException("BODY_PROVIDER_COUNT");}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("player-body-provider.json"),new ObjectMapper().writeValueAsString(Map.of("localhostCalls",calls.get(),"paidCalls",0)));}
}
