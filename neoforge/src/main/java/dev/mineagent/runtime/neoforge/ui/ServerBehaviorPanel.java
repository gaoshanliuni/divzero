package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.skill.*;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Built-in panel projection; shares the exact skill/tool authority and mutations. */
public final class ServerBehaviorPanel {
    public static CompletableFuture<Map<String,Object>> read(ServerPlayer player,UUID agent){
        if(!ServerTaskStart.allowed(player,agent))return CompletableFuture.failedFuture(new SecurityException("BEHAVIOR_PERMISSION"));
        return SkillRuntime.get(player.level().getServer()).inspect(player,agent).thenApply(data->{
            var result=new LinkedHashMap<>(panelProjection(data));var context=new LinkedHashMap<String,Object>();context.put("ownerId",player.getUUID().toString());context.put("dimension",player.level().dimension().identifier().toString());context.put("position",List.of(player.getX(),player.getY(),player.getZ()));
            var hit=player.pick(16,0,false);context.put("look",hit.getType()==HitResult.Type.BLOCK?List.of(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z):List.of());
            context.put("entities",player.level().getEntitiesOfClass(LivingEntity.class,player.getBoundingBox().inflate(32),e->e!=player&&e.isAlive()).stream().map(e->Map.of("id",e.getUUID().toString(),"name",e.getName().getString())).toList());result.put("context",context);var runtime=SkillRuntime.get(player.level().getServer());result.put("defaults",Map.of("ai",runtime.savedPolicy(player.getUUID(),agent,"ai"),"player",runtime.savedPolicy(player.getUUID(),agent,"player")));result.put("enhancements",ActorEnhancements.inspect(player,agent));result.put("regions",Map.of("ai",ServerBehaviorRegions.read(player,agent,"ai"),"player",ServerBehaviorRegions.read(player,agent,"player")));return result;
        });
    }
    static Map<String,Object> panelProjection(Map<String,Object> data){return dev.mineagent.runtime.core.ui.BehaviorPanelProjection.snapshot(data);}
    static Map<String,Object> panelReceipt(Map<String,Object> data){return dev.mineagent.runtime.core.ui.BehaviorPanelProjection.receipt(data);}
    public static CompletableFuture<Map<String,Object>> write(ServerPlayer player,UUID agent,UUID operation,String tool,String source,BooleanSupplier current)throws Exception{
        if(!ServerTaskStart.allowed(player,agent)||!current.getAsBoolean())throw new SecurityException("BEHAVIOR_PERMISSION");
        if(tool.equals("set_respawn_policy")){var n=new ObjectMapper().readTree(source);if(n.size()!=2||!n.path("enabled").isBoolean()||!n.path("expected_revision").isIntegralNumber())throw new IllegalArgumentException("RESPAWN_POLICY_ARGUMENTS");return CompletableFuture.completedFuture(dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.bodies(player.level().getServer()).setAutoRespawn(agent,n.path("expected_revision").asLong(),n.path("enabled").asBoolean()));}
        if(tool.equals("respawn_now")){if(!new ObjectMapper().readTree(source).isEmpty())throw new IllegalArgumentException("RESPAWN_POLICY_ARGUMENTS");return CompletableFuture.completedFuture(dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.bodies(player.level().getServer()).respawnNow(agent));}
        if(tool.equals("set_game_mode")||tool.equals("teleport_to_owner")){
            var body=dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.bodies(player.level().getServer()).body(agent).orElseThrow(()->new IllegalStateException("AI_BODY_NOT_LOADED"));
            if(!body.isAlive())throw new IllegalStateException("AI_BODY_NOT_ALIVE");var n=new ObjectMapper().readTree(source);
            if(tool.equals("set_game_mode")){
                if(n.size()!=2||!n.path("mode").isTextual()||!n.path("expected_mode").asText().equals(body.gameMode.getGameModeForPlayer().name()))throw new IllegalStateException("GAME_MODE_CHANGED");
                var mode=net.minecraft.world.level.GameType.valueOf(n.get("mode").asText());
                if(!body.setGameMode(mode)&&body.gameMode.getGameModeForPlayer()!=mode)throw new IllegalStateException("GAME_MODE_CHANGE_REJECTED");
                player.level().getServer().getPlayerList().saveAll();
                return CompletableFuture.completedFuture(Map.of("status","APPLIED","mode",body.gameMode.getGameModeForPlayer().name()));
            }
            if(!n.isEmpty())throw new IllegalArgumentException("TELEPORT_ARGUMENTS");
            if(!body.teleportTo(player.level(),player.getX(),player.getY(),player.getZ(),Set.of(),body.getYRot(),body.getXRot(),true))throw new IllegalStateException("TELEPORT_REJECTED");
            return CompletableFuture.completedFuture(Map.of("status","APPLIED","dimension",body.level().dimension().identifier().toString(),"position",List.of(body.getX(),body.getY(),body.getZ())));
        }
        if(tool.equals("set_actor_enhancements")){
            var n=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(source);
            if(n.path("resetWeights").asBoolean()&&!n.path("confirmedReset").asBoolean(false))throw new IllegalArgumentException("RESET_WEIGHTS_CONFIRMATION_REQUIRED");n.remove("confirmedReset");
            return CompletableFuture.completedFuture(ActorEnhancements.update(player,agent,n));
        }
        var raw=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(source);
        var region=raw.has("panel_region")?ServerBehaviorRegions.prepare(player,agent,raw.remove("panel_region")):null;
        if(region!=null&&!Set.of("set_behavior_mode","set_combat_policy").contains(tool))throw new IllegalArgumentException("REGION_TOOL");
        source=raw.toString();
        var authority=BehaviorAuthority.get(player.level().getServer());authority.invalidate(player,agent);long revision=authority.revision(player,agent);BooleanSupplier live=()->current.getAsBoolean()&&authority.revision(player,agent)==revision;
        var runtime=SkillRuntime.get(player.level().getServer());
        if(tool.equals("stop_all")){runtime.stopAll(player,agent);return CompletableFuture.completedFuture(Map.of("status","STOPPED"));}
        if(!Set.of("set_behavior_mode","set_combat_policy","control_behavior").contains(tool))throw new IllegalArgumentException("BEHAVIOR_PANEL_TOOL");
        String canonical=dev.mineagent.runtime.core.task.SkillTools.canonical(tool,source);
        var arguments=new ObjectMapper().readTree(canonical);var combat=arguments.path("combat");
        if(combat.path("engagement").asText().equals("SPECIFIED")&&player.level().getEntity(UUID.fromString(combat.path("target").asText())) instanceof ServerPlayer target)PvpConsent.grantFromPanel(player,agent,target);
        return runtime.execute(player,agent,operation,null,tool,arguments,live).thenCompose(value->player.level().getServer().submit(()->{
            if(region!=null&&Set.of("APPLIED","STARTED").contains(Objects.toString(value.get("status"))))try{if(!live.getAsBoolean())throw new IllegalStateException("REGION_CONTEXT_CHANGED");ServerBehaviorRegions.save(player,agent,region);}catch(Exception failed){return Map.<String,Object>of("status","PARTIAL","error",Objects.toString(failed.getMessage(),"REGION_SAVE_FAILED"),"workApplied",true);}
            return value;
        }));
    }
    private ServerBehaviorPanel(){}
}
