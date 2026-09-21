package dev.mineagent.runtime.neoforge.host;
import dev.mineagent.runtime.core.host.HostCommandRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
/** Client implementation is installed only on Dist.CLIENT. Dedicated/LAN peers cannot acquire it. */
public final class LocalHostCommands {
    public interface Endpoint {boolean matches(MinecraftServer server,UUID owner);Map<String,Object> inspect();CompletableFuture<Map<String,Object>> request(MinecraftServer server,UUID owner,HostCommandRequest request);void cancel(UUID operation);}
    private static volatile Endpoint endpoint;
    private LocalHostCommands(){}
    public static void install(Endpoint value){if(endpoint!=null&&endpoint!=value)throw new IllegalStateException("HOST_ENDPOINT_EXISTS");endpoint=value;}
    private static Endpoint local(ServerPlayer p){var s=p.level().getServer();if(!s.isSameThread()||!s.isSingleplayerOwner(p.nameAndId())||endpoint==null||!endpoint.matches(s,p.getUUID()))throw new SecurityException("HOST_LOCAL_OWNER_REQUIRED");return endpoint;}
    public static Map<String,Object> inspect(ServerPlayer p){try{return local(p).inspect();}catch(SecurityException e){return Map.of("available",false,"error","HOST_LOCAL_OWNER_REQUIRED","remoteServerAccess",false);}}
    public static CompletableFuture<Map<String,Object>> request(ServerPlayer p,HostCommandRequest request){try{return local(p).request(p.level().getServer(),p.getUUID(),request);}catch(SecurityException e){return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","HOST_LOCAL_OWNER_REQUIRED"));}}
    public static void cancel(UUID operation){var e=endpoint;if(e!=null)e.cancel(operation);}
}
