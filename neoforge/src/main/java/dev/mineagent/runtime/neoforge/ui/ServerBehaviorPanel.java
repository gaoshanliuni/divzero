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
            var result=new LinkedHashMap<>(data);var context=new LinkedHashMap<String,Object>();context.put("dimension",player.level().dimension().identifier().toString());context.put("position",List.of(player.getX(),player.getY(),player.getZ()));
            var hit=player.pick(16,0,false);context.put("look",hit.getType()==HitResult.Type.BLOCK?List.of(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z):List.of());
            context.put("entities",player.level().getEntitiesOfClass(LivingEntity.class,player.getBoundingBox().inflate(32),e->e!=player&&e.isAlive()).stream().map(e->Map.of("id",e.getUUID().toString(),"name",e.getName().getString())).toList());result.put("context",context);return result;
        });
    }
    public static CompletableFuture<Map<String,Object>> write(ServerPlayer player,UUID agent,UUID operation,String tool,String source,BooleanSupplier current)throws Exception{
        if(!ServerTaskStart.allowed(player,agent)||!current.getAsBoolean())throw new SecurityException("BEHAVIOR_PERMISSION");
        var authority=BehaviorAuthority.get(player.level().getServer());authority.invalidate(player,agent);long revision=authority.revision(player,agent);BooleanSupplier live=()->current.getAsBoolean()&&authority.revision(player,agent)==revision;
        var runtime=SkillRuntime.get(player.level().getServer());
        if(tool.equals("stop_all")){runtime.stopAll(player,agent);return CompletableFuture.completedFuture(Map.of("status","STOPPED"));}
        if(!Set.of("set_behavior_mode","set_combat_policy","control_behavior").contains(tool))throw new IllegalArgumentException("BEHAVIOR_PANEL_TOOL");
        String canonical=dev.mineagent.runtime.core.task.SkillTools.canonical(tool,source);
        var arguments=new ObjectMapper().readTree(canonical);var combat=arguments.path("combat");
        if(combat.path("engagement").asText().equals("SPECIFIED")&&player.level().getEntity(UUID.fromString(combat.path("target").asText())) instanceof ServerPlayer target)PvpConsent.grantFromPanel(player,agent,target);
        return runtime.execute(player,agent,operation,null,tool,arguments,live);
    }
    private ServerBehaviorPanel(){}
}
