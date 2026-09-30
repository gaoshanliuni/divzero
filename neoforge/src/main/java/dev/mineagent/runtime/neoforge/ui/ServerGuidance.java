package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.conversation.ScopedGuidance;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Only fixed managed locations or owned, version-pinned package resources may be read. */
public final class ServerGuidance {
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    public static CompletableFuture<Map<String,Object>> read(ServerPlayer player,UUID agent,JsonNode args,BooleanSupplier permit){
        if(!ServerTaskStart.allowed(player,agent)||!permit.getAsBoolean())throw new SecurityException("GUIDANCE_PERMISSION");
        var server=player.level().getServer();String scope=args.path("scope").asText();int offset=args.path("offset").asInt(),length=args.path("length").asInt(4096);
        CompletableFuture<Map<String,Object>> result;
        if(scope.equals("package"))result=ServerPackageRuntime.get(server).guidance(player,UUID.fromString(args.path("package_id").asText()),args.path("revision").asLong(),args.path("path").asText("AGENTS.md"),offset,length);
        else {
            if(!Set.of("global","world").contains(scope))throw new IllegalArgumentException("GUIDANCE_SCOPE");
            var root=scope.equals("global")?server.getServerDirectory():server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            var path=scope.equals("global")?root.resolve("config/divzero-guidance/global.md"):root.resolve("divzero-guidance/world.md");
            String source=scope+"://"+MineAgentRuntimeServices.worldId(server)+"/"+(scope.equals("global")?"global.md":"world.md");
            result=CompletableFuture.supplyAsync(()->{try{return ScopedGuidance.file(root,path,source,offset,length);}catch(Exception failure){throw new CompletionException(failure);}},IO);
        }
        return result.thenCompose(value->server.submit(()->{if(!permit.getAsBoolean()||server.getPlayerList().getPlayer(player.getUUID())!=player||!ServerTaskStart.allowed(player,agent))throw new SecurityException("GUIDANCE_CONTEXT_CHANGED");return value;}));
    }
    private ServerGuidance(){}
}
