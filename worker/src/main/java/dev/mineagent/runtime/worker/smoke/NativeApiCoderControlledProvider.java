package dev.mineagent.runtime.worker.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local OpenAI-compatible Coder fixture; validates Native context transport, never model quality. */
final class NativeApiCoderControlledProvider implements AutoCloseable {
    private final HttpServer http;private final Path game;private final AtomicInteger calls=new AtomicInteger();private final ObjectMapper json=new ObjectMapper();
    NativeApiCoderControlledProvider(Path game)throws Exception{
        this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{try{String request=new String(exchange.getRequestBody().readNBytes(8*1024*1024+1),StandardCharsets.UTF_8);if(request.length()>8*1024*1024)throw new IllegalArgumentException("NATIVE_CODER_FIXTURE_REQUEST_LIMIT");int count=calls.incrementAndGet();if(!request.contains("FML_TRANSFORM_PIPELINE_PREDEFINE")||!request.contains("net.minecraft.server.MinecraftServer")||!request.contains("SELECTED_RAW_AND_EXPLICIT_LIVE_OVERLAY_CONTEXT_NO_EXECUTION"))throw new IllegalStateException("NATIVE_CODER_CONTEXT_MISSING");Files.writeString(game.resolve("native-api-coder-request.json"),request);String source="package dev.mineagent.smoke; import java.util.Map; import dev.mineagent.runtime.scripting.javaext.RuntimeExtension; public final class CoderOverlayExtension implements RuntimeExtension { public Object start(Map<String,Object> bindings){ return \"CEF_CODER_OVERLAY_STARTED\"; } }";String content=json.writeValueAsString(Map.of("source",source));byte[] response=json.writeValueAsBytes(Map.of("model","controlled-native-coder","choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(Exception failure){Files.writeString(game.resolve("native-api-coder-provider-failure.txt"),failure.toString());byte[] response="{\"error\":{\"code\":\"CONTROLLED_NATIVE_CODER_FAILED\"}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}});http.start();
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-native-coder","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("NATIVE_CODER_FIXTURE_CONFIG");}
    }
    void verify()throws Exception{if(calls.get()!=1||!Files.isRegularFile(game.resolve("native-api-coder-request.json"))||Files.exists(game.resolve("native-api-coder-provider-failure.txt")))throw new IllegalStateException("NATIVE_CODER_FIXTURE_CALLS");}
    @Override public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("native-api-coder-provider.json"),json.writeValueAsString(Map.of("kind","CONTROLLED_LOCAL_CODER_NOT_MODEL_QUALITY","calls",calls.get(),"paidCalls",0)));}
}
