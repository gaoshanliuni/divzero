package dev.mineagent.runtime.worker.smoke;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Isolated transport fixture. No DeepSeek or other paid endpoint. */
final class DesktopWindowsControlledProvider implements AutoCloseable {
    private final HttpServer http;private final Path game;private final AtomicInteger calls=new AtomicInteger();
    DesktopWindowsControlledProvider(Path game)throws Exception{this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",e->{try{
        int n=calls.incrementAndGet();String input=new String(e.getRequestBody().readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);
        if(n>3||!input.contains("桌面窗口助手")||!input.contains("WebGUI 宿主状态"))throw new IllegalArgumentException("DESKTOP_PROVIDER_REQUEST");
        if(n==3)Thread.sleep(2500);
        byte[] output=new ObjectMapper().writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content","{\"pinned\":"+(n!=2)+"}")))));
        e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);
    }catch(Exception error){Files.writeString(game.resolve("desktop-provider-failure.txt"),error.toString());}finally{e.close();}});http.start();
    try(var cfg=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!cfg.apply(new dev.mineagent.runtime.api.config.ConfigPatch(cfg.snapshot().revision(),Map.of("provider.openai.enabled","true","provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","local-desktop","provider.openai.apiKey","fixture-only","runtime.initialized","true","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("DESKTOP_PROVIDER_CONFIG");}}
    void verify(){if(calls.get()!=3||Files.exists(game.resolve("desktop-provider-failure.txt")))throw new IllegalStateException("DESKTOP_PROVIDER_COUNT");}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("desktop-provider.json"),new ObjectMapper().writeValueAsString(Map.of("localhostCalls",calls.get(),"paidCalls",0)));}
}
