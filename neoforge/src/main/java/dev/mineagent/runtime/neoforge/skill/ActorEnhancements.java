package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Durable per-world, per-body preferences; a live task/lease is still required for every effect. */
public final class ActorEnhancements {
    public record Settings(long revision,boolean boost,boolean learning,boolean neural,boolean recovery,
                           boolean enhancedCritical,boolean microHop,double horizontalKnockback,double verticalKnockback){}
    private static String key(ServerPlayer player,UUID body){return "enhancements."+MineAgentRuntimeServices.worldId(player.level().getServer())+"."+body+".";}
    public static UUID bodyId(ServerPlayer viewer,UUID agent,String actor){if(!Set.of("ai","player").contains(actor))throw new IllegalArgumentException("ENHANCEMENT_ACTOR");return actor.equals("player")?viewer.getUUID():agent;}
    public static Settings read(ServerPlayer context,UUID body){
        var values=MineAgentRuntimeServices.config(context.level().getServer()).snapshot().values();String p=key(context,body);
        return new Settings(Long.parseLong(values.getOrDefault(p+"revision","0")),Boolean.parseBoolean(values.getOrDefault(p+"boost","false")),Boolean.parseBoolean(values.getOrDefault(p+"learning","false")),Boolean.parseBoolean(values.getOrDefault(p+"neural","true")),Boolean.parseBoolean(values.getOrDefault(p+"recovery","true")),Boolean.parseBoolean(values.getOrDefault(p+"enhancedCritical","true")),Boolean.parseBoolean(values.getOrDefault(p+"microHop","true")),Double.parseDouble(values.getOrDefault(p+"horizontalKnockback","0.5")),Double.parseDouble(values.getOrDefault(p+"verticalKnockback","0.5")));
    }
    public static Settings forBody(ServerPlayer body){return read(body,body instanceof MineAgentPlayer ai?ai.agentId():body.getUUID());}
    public static boolean executing(ServerPlayer body){
        if(!body.isAlive()||body.isRemoved()||body.isSpectator())return false;
        if(body instanceof MineAgentPlayer ai){var owner=body.level().getServer().getPlayerList().getPlayer(ai.ownerPlayerId());return owner!=null&&ai.canAct()&&ServerTaskStart.allowed(owner,ai.agentId());}
        return AutonomousPlayerAgent.emergencySession(body)!=null;
    }
    public static boolean boost(ServerPlayer body){return executing(body)&&forBody(body).boost&&Boolean.parseBoolean(MineAgentRuntimeServices.config(body.level().getServer()).snapshot().values().getOrDefault("autonomy.boost.allowed","true"));}
    public static Map<String,Object> inspect(ServerPlayer viewer,UUID agent){
        if(!ServerTaskStart.allowed(viewer,agent))throw new SecurityException("ENHANCEMENT_PERMISSION");
        return Map.of("ai",read(viewer,agent),"player",read(viewer,viewer.getUUID()),"boostAllowed",Boolean.parseBoolean(MineAgentRuntimeServices.config(viewer.level().getServer()).snapshot().values().getOrDefault("autonomy.boost.allowed","true")));
    }
    public static Map<String,Object> update(ServerPlayer viewer,UUID agent,JsonNode input){
        if(!viewer.level().getServer().isSameThread()||!ServerTaskStart.allowed(viewer,agent))throw new SecurityException("ENHANCEMENT_PERMISSION");
        for(var entry:input.properties())if(!Set.of("actor","expected_revision","boost","learning","neural","recovery","enhancedCritical","microHop","horizontalKnockback","verticalKnockback").contains(entry.getKey()))throw new IllegalArgumentException("ENHANCEMENT_FIELD");
        UUID body=bodyId(viewer,agent,input.path("actor").asText("ai"));var old=read(viewer,body);
        if(!input.path("expected_revision").isIntegralNumber()||input.get("expected_revision").asLong()!=old.revision)throw new IllegalStateException("ENHANCEMENT_VERSION_CHANGED");
        var service=MineAgentRuntimeServices.config(viewer.level().getServer());var config=service.snapshot();String p=key(viewer,body);var patch=new LinkedHashMap<String,String>();
        for(String field:List.of("boost","learning","neural","recovery","enhancedCritical","microHop"))if(input.has(field)){if(!input.get(field).isBoolean())throw new IllegalArgumentException("ENHANCEMENT_BOOLEAN_"+field);patch.put(p+field,input.get(field).asText());}
        for(String field:List.of("horizontalKnockback","verticalKnockback"))if(input.has(field)){double value=input.get(field).asDouble(Double.NaN);if(!input.get(field).isNumber()||!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("ENHANCEMENT_RATIO_"+field);patch.put(p+field,Double.toString(value));}
        if(input.path("boost").asBoolean()&&!Boolean.parseBoolean(config.values().getOrDefault("autonomy.boost.allowed","true")))throw new SecurityException("BOOST_DISABLED_BY_SERVER");
        patch.put(p+"revision",Long.toString(Math.addExact(old.revision,1)));
        if(!service.apply(new ConfigPatch(config.revision(),patch),true).accepted())throw new IllegalStateException("ENHANCEMENT_VERSION_CHANGED");
        return Map.of("status","APPLIED","workPreserved",true,"body",body,"settings",read(viewer,body));
    }
    private ActorEnhancements(){}
}
