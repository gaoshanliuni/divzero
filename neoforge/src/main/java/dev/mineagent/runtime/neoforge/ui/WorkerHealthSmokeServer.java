package dev.mineagent.runtime.neoforge.ui;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.*;
import net.minecraft.server.MinecraftServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A loopback response waits for real B login and continued Native ticks. Not a real model or application. */
public final class WorkerHealthSmokeServer {
    private static HttpServer provider;private static CompletableFuture<dev.mineagent.runtime.api.worker.WorkerEnvelope> future;private static final CountDownLatch release=new CountDownLatch(1);
    private static final AtomicInteger calls=new AtomicInteger();private static final AtomicBoolean entered=new AtomicBoolean();private static int bTick=-1,samples;private static long maxNanos;private static boolean verified;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.workerHealthSmoke");}
    public static void tick(MinecraftServer server)throws Exception{
        if(!enabled()||verified)return;
        boolean a=server.getPlayerList().getPlayers().stream().anyMatch(p->p.nameAndId().name().equals("DeliveryA"));
        if(future==null&&a){
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);provider.createContext("/v1/chat/completions",exchange->{
                try{exchange.getRequestBody().readAllBytes();calls.incrementAndGet();entered.set(true);if(!release.await(75,TimeUnit.SECONDS))throw new IllegalStateException("HEALTH_NATIVE_TICK_NOT_RELEASED");byte[] data="{\"choices\":[{\"message\":{\"content\":\"WORKER_HEALTH_REPLY\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);}
                catch(Exception failure){exchange.close();}finally{exchange.close();}
            });provider.start();var config=MineAgentRuntimeServices.config(server);var values=Map.of("provider.openai.baseUrl","http://127.0.0.1:"+provider.getAddress().getPort()+"/v1/","provider.openai.model","controlled-worker-health","provider.openai.apiKey","fixture-only","voice.output.enabled","false");if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),values),true).accepted())throw new IllegalStateException("HEALTH_PROVIDER_CONFIG");
            future=MineAgentRuntimeServices.worker(server).complete(config,"SEMANTIC","Controlled transport wait, not an application request");
        }
        if(entered.get()&&!future.isDone()){
            long before=System.nanoTime();boolean alive=MineAgentRuntimeServices.worker(server).isAlive();long elapsed=System.nanoTime()-before;if(!alive||elapsed>100_000_000L)throw new IllegalStateException("HEALTH_MAIN_THREAD_BLOCKED");maxNanos=Math.max(maxNanos,elapsed);samples++;
            boolean b=server.getPlayerList().getPlayers().stream().anyMatch(p->p.nameAndId().name().equals("DeliveryB"));if(b&&bTick<0)bTick=server.getTickCount();if(bTick>=0&&server.getTickCount()-bTick>=60)release.countDown();
        }
        if(future!=null&&future.isDone()){
            var response=future.join();if(!response.type().equals("model.result")||!response.payload().getOrDefault("text","").equals("WORKER_HEALTH_REPLY")||calls.get()!=1||samples<60||bTick<0)throw new IllegalStateException("HEALTH_PROBE_SCOPE");provider.stop(0);verified=true;Path root=server.getServerDirectory().resolve("worker-health-evidence");Files.createDirectories(root);Files.writeString(root.resolve("result.json"),new ObjectMapper().writeValueAsString(Map.of("status","REAL_SECOND_CLIENT_LOGIN_DURING_BLOCKED_PROVIDER_VERIFIED","controlledProviderCalls",calls.get(),"nativeHealthSamples",samples,"maxHealthReadNanos",maxNanos,"bJoinedDuringRequest",true,"systemInputInjected",false,"realModelCalls",0,"replyVerified",true)));
            MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORKER_HEALTH_OK samples={} maxNanos={}",samples,maxNanos);
        }
    }
}
