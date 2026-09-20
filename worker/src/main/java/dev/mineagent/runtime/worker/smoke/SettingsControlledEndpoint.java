package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit local configuration probes only. The saved settings themselves never call this endpoint. */
final class SettingsControlledEndpoint implements AutoCloseable {
    private final HttpServer http;private final Path game;private final ObjectMapper json=new ObjectMapper();private final AtomicInteger calls=new AtomicInteger();
    SettingsControlledEndpoint(Path game)throws Exception{
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{try{
            var body=json.readTree(exchange.getRequestBody().readNBytes(262145));int count=calls.incrementAndGet();boolean authenticated="Bearer native-fixture-key-only".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            Files.writeString(game.resolve("settings-probe-"+count+".json"),json.writeValueAsString(Map.of("model",body.path("model").asText(),"authenticated",authenticated,"paidCalls",0)));
            Files.writeString(game.resolve("settings-probe-count.json"),json.writeValueAsString(Map.of("calls",count,"paidCalls",0)));
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","settings-probe-ok")))));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }finally{exchange.close();}});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","initial-model","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("SETTINGS_FIXTURE_CONFIG");}
    }
    void verify()throws Exception{if(calls.get()!=2)throw new IllegalStateException("SETTINGS_PROBE_COUNT");for(int i=1;i<=2;i++){var value=json.readTree(Files.readString(game.resolve("settings-probe-"+i+".json")));if(!value.path("authenticated").asBoolean()||!value.path("model").asText().equals("configured-model"))throw new IllegalStateException("SETTINGS_PROBE_CONFIGURATION");}}
    @Override public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("settings-probes.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_LOCAL_CONFIGURATION_PROBE_NOT_MODEL_QUALITY","calls",calls.get(),"paidCalls",0)));}
}
