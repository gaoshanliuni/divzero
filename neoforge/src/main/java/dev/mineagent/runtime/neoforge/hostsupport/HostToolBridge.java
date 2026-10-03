package dev.mineagent.runtime.neoforge.hostsupport;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.hostsupport.PythonEdition;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Optional feature registration. The no-Python artifact contains no process executor or provider. */
public final class HostToolBridge {
    public interface Provider {
        CompletableFuture<Map<String,Object>> execute(ServerPlayer p,UUID agent,UUID operation,String tool,JsonNode args,BooleanSupplier current);
        default Map<String,Object> pending(){return Map.of();}
        default boolean legacySmokeTick(net.minecraft.server.MinecraftServer server)throws Exception{return false;}
        default void observe(String tool,JsonNode args,Map<String,Object> result){}
    }
    private static volatile Provider provider;
    public static void install(Provider value){if(!PythonEdition.bundled())throw new IllegalStateException("PYTHON_UNSUPPORTED_EDITION");if(provider!=null&&provider!=value)throw new IllegalStateException("HOST_PROVIDER_ALREADY_INSTALLED");provider=value;}
    public static CompletableFuture<Map<String,Object>> execute(ServerPlayer p,UUID agent,UUID operation,String tool,JsonNode args,BooleanSupplier current){var value=provider;return value==null?CompletableFuture.completedFuture(PythonEdition.bundled()?Map.of("status","REJECTED","error","HOST_LOCAL_OWNER_REQUIRED","executionState","NOT_STARTED"):PythonEdition.unavailable()):value.execute(p,agent,operation,tool,args,current);}
    public static Map<String,Object> pending(){var value=provider;return value==null?Map.of():value.pending();}
    public static void observe(String tool,JsonNode args,Map<String,Object> result){var value=provider;if(value!=null)value.observe(tool,args,result);}
    public static boolean legacySmokeTick(net.minecraft.server.MinecraftServer server)throws Exception{var value=provider;return value!=null&&value.legacySmokeTick(server);}
    private HostToolBridge(){}
}
