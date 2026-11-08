package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.config.PermissionConfig;
import dev.mineagent.runtime.neoforge.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Two real clients connect sequentially to an isolated dedicated server. No production player names match. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class MultiplayerBlueprintSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();private static final Map<UUID,UUID> agents=new HashMap<>();private static final Set<String> verifying=new HashSet<>();private static CompletableFuture<?> initialization;private static boolean done;private static int ticks,finishAt;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.multiplayerBlueprintSmoke")||done)return;var server=event.getServer();
        try{
            if(!server.isDedicatedServer())throw new IllegalStateException("FIXTURE_REQUIRES_DEDICATED_SERVER");Path root=Path.of(System.getProperty("mineagent.multiplayerBlueprintRoot"));Files.createDirectories(root);
            if(++ticks>24000)throw new IllegalStateException("BLUEPRINT_MULTIPLAYER_TIMEOUT");
            if(finishAt>0){if(ticks>=finishAt){done=true;server.halt(false);}return;}
            if(!WorldIdentityRuntime.ready(server)){if(initialization==null)initialization=WorldIdentityRuntime.accept(server.createCommandSourceStack());if(initialization.isCompletedExceptionally())initialization.join();return;}
            for(var player:server.getPlayerList().getPlayers()){
                String name=player.getGameProfile().name();if(!Set.of("BlueprintA","BlueprintB").contains(name))continue;
                if(WorldActivationRuntime.state(player)!=dev.mineagent.runtime.core.permission.WorldActivation.State.ENABLED)continue;
                if(player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new IllegalStateException("BLUEPRINT_FIXTURE_PLAYER_IS_OP");
                if(!agents.containsKey(player.getUUID())){
                    grant(player,name.equals("BlueprintA")?Set.of(PermissionAction.EDIT_WORLD):Set.of());
                    var agent=MineAgentRuntimeServices.bodies(server).createPersistentAt(name+"_AI",player.getUUID(),player.level(),player.position().add(3,0,0)).agentId();agents.put(player.getUUID(),agent);
                    Files.writeString(root.resolve(name+"-ready.json"),JSON.writeValueAsString(Map.of("agent",agent,"owner",player.getUUID(),"operator",false)));
                }
                if(name.equals("BlueprintB")&&Files.exists(root.resolve("BlueprintB-denied.json"))&&!Files.exists(root.resolve("BlueprintB-granted.json"))){grant(player,Set.of(PermissionAction.EDIT_WORLD));Files.writeString(root.resolve("BlueprintB-granted.json"),"{\"granted\":true}");}
                if(Files.exists(root.resolve(name+"-uploaded.json"))&&verifying.add(name)){
                    var uploaded=JSON.readTree(Files.readString(root.resolve(name+"-uploaded.json")));var args=JSON.createObjectNode().put("file_id",uploaded.path("asset").asText());
                    ServerBuildingFiles.read(player,agents.get(player.getUUID()),"inspect_building_file",args,()->server.getPlayerList().getPlayer(player.getUUID())==player).whenComplete((read,error)->server.execute(()->{
                        try{if(error!=null||read==null||!Integer.valueOf(2).equals(((Number)read.getOrDefault("totalNonAir",-1)).intValue()))throw new IllegalStateException("DEDICATED_BLUEPRINT_PARSE_FAILED_"+read,error);
                            Files.writeString(root.resolve(name+"-verified.json"),JSON.writeValueAsString(Map.of("status","PASS","totalNonAir",2,"dedicated",true,"owner",player.getUUID(),"asset",uploaded.path("asset").asText(),"modelCalls",0)));
                        }catch(Exception failure){fail(server,root,failure);}
                    }));
                }
            }
            if(Files.exists(root.resolve("BlueprintA-verified.json"))&&Files.exists(root.resolve("BlueprintB-verified.json"))){Files.writeString(root.resolve("server-result.json"),JSON.writeValueAsString(Map.of("status","PASS","dedicatedServer",true,"realClientIdentities",2,"authorizedUploads",2,"foreignAssetRejected",Files.exists(root.resolve("BlueprintB-foreign-denied.json")),"initialGrantDenied",Files.exists(root.resolve("BlueprintB-denied.json")),"modelCalls",0)));finishAt=ticks+100;}
        }catch(Exception failure){fail(server,Path.of(System.getProperty("mineagent.multiplayerBlueprintRoot","blueprint-smoke")),failure);}
    }
    private static void grant(net.minecraft.server.level.ServerPlayer player,Set<PermissionAction> actions){var server=player.level().getServer();var config=MineAgentRuntimeServices.config(server);if(!PermissionConfig.apply(config,MineAgentRuntimeServices.permissions(server),config.revision(),player.getUUID(),actions,true).accepted())throw new IllegalStateException("FIXTURE_GRANT_FAILED");}
    private static void fail(net.minecraft.server.MinecraftServer server,Path root,Throwable error){try{Files.createDirectories(root);Files.writeString(root.resolve("server-failure.json"),JSON.writeValueAsString(Map.of("error",error.toString(),"ticks",ticks)));}catch(Exception ignored){}done=true;server.halt(false);}
    private MultiplayerBlueprintSmokeServer(){}
}
