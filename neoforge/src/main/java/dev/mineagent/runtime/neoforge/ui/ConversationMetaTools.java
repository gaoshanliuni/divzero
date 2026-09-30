package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;

/** Resident observations and stop entry point; disclosure itself never starts/stops runtime skills. */
final class ConversationMetaTools {
    private ConversationMetaTools(){}
    static CompletableFuture<Map<String,Object>> observe(ServerPlayer player,UUID agent){
        var server=player.level().getServer();var result=new LinkedHashMap<String,Object>();
        result.put("status","OBSERVED");result.put("observedGameTick",server.getTickCount());
        result.put("player",Map.of("id",player.getUUID(),"dimension",player.level().dimension().identifier().toString(),"position",List.of(player.getX(),player.getY(),player.getZ()),"health",player.getHealth(),"food",player.getFoodData().getFoodLevel(),"gameMode",player.gameMode.getGameModeForPlayer().getName(),"mainHand",player.getMainHandItem().toString()));
        var body=MineAgentRuntimeServices.bodies(server).body(agent);
        result.put("agent",body.<Object>map(value->Map.of("id",agent,"name",value.getName().getString(),"dimension",value.level().dimension().identifier().toString(),"position",List.of(value.getX(),value.getY(),value.getZ()),"health",value.getHealth(),"alive",value.isAlive(),"gameMode",value.gameMode.getGameModeForPlayer().getName())).orElse(Map.of("id",agent,"state","NOT_LOADED")));
        result.put("playerControl",dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent.inspect(player));
        boolean allowed=ServerTaskStart.allowed(player,agent);result.put("canControlAgent",allowed);
        if(!allowed)return CompletableFuture.completedFuture(result);
        return dev.mineagent.runtime.neoforge.skill.SkillRuntime.get(server).inspect(player,agent).thenApply(value->{result.put("behavior",compact(value));return result;});
    }
    private static Object compact(Map<String,Object> value){
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value);var rows=new ArrayList<Object>();
        for(var entry:json.path("skills")){var session=entry.path("session");String state=session.path("state").asText();if(Set.of("COMPLETED","FAILED","CANCELLED").contains(state))continue;
            var row=new LinkedHashMap<String,Object>();row.put("id",session.path("spec").path("id").asText());row.put("revision",session.path("revision").asLong());row.put("mode",session.path("spec").path("kind").asText());row.put("state",state);row.put("phase",session.path("phase").asText());row.put("reason",session.path("reason").asText());row.put("counters",session.path("counters"));rows.add(row);
        }return Map.of("active",rows);
    }
    static Map<String,Object> stop(ServerPlayer player,UUID agent){
        var server=player.level().getServer();if(!server.isSameThread()||server.getPlayerList().getPlayer(player.getUUID())!=player)throw new SecurityException("STOP_CALLER_CONTEXT");
        dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent.stopForPlayer(player);
        boolean allowed=ServerTaskStart.allowed(player,agent),changed=false;
        if(allowed)changed=dev.mineagent.runtime.neoforge.skill.SkillRuntime.get(server).stopAll(player,agent);
        else ServerConversations.stopBehaviorRequests(player,agent);
        return Map.of("status",allowed?"STOPPED":"CALLER_CONTROL_STOPPED","agentActionsStopped",allowed,"changed",changed,"worldChangesRolledBack",false,"permission",allowed?"ALLOWED":"AGENT_CONTROL_DENIED","automaticResume",false);
    }
}
