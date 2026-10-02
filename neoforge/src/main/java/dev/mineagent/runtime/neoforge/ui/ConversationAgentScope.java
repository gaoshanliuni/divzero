package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.api.agent.AgentDefinition;
import dev.mineagent.runtime.core.conversation.AgentToolScope;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Keep the real requesting player; selecting a sibling AI grants no new authority. */
public final class ConversationAgentScope {
    private static AgentDefinition definition(ServerPlayer player,UUID id){return MineAgentRuntimeServices.bodies(player.level().getServer()).definitions().stream().filter(a->a.agentId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("AGENT_NOT_FOUND"));}
    public static boolean allowed(ServerPlayer player,UUID source,UUID target){
        if(!player.level().getServer().isSameThread())return false;
        try{return AgentToolScope.sameOwner(player.getUUID(),definition(player,source).ownerPlayerId(),definition(player,target).ownerPlayerId())&&ServerTaskStart.allowed(player,source)&&ServerTaskStart.allowed(player,target);}
        catch(IllegalArgumentException absent){return false;}
    }
    static Map<String,Object> list(ServerPlayer player,UUID source,JsonNode args){
        if(!allowed(player,source,source))throw new SecurityException("AGENT_SAME_OWNER_REQUIRED");
        var bodies=MineAgentRuntimeServices.bodies(player.level().getServer());String query=args.path("query").asText("").toLowerCase(Locale.ROOT);int offset=args.path("offset").asInt(0);
        var all=bodies.definitions().stream().filter(a->a.ownerPlayerId().equals(player.getUUID())).filter(a->a.displayName().toLowerCase(Locale.ROOT).contains(query)||a.agentId().toString().contains(query)).sorted(Comparator.comparing(a->a.agentId().toString())).toList();
        var rows=new ArrayList<Map<String,Object>>();for(var a:all.stream().skip(offset).limit(16).toList()){
            var row=new LinkedHashMap<String,Object>();row.put("agent_id",a.agentId());row.put("name",a.displayName());row.put("revision",bodies.revision(a.agentId()));row.put("bodyState",bodies.bodyState(a.agentId()));row.put("current",a.agentId().equals(source));row.put("canConfigure",ServerTaskStart.allowed(player,a.agentId()));
            var body=bodies.body(a.agentId()).orElse(null);row.put("dimension",body==null?"":body.level().dimension().identifier().toString());row.put("position",body==null?List.of():List.of(body.getX(),body.getY(),body.getZ()));row.put("gameMode",body==null?"NOT_LOADED":body.gameMode.getGameModeForPlayer().name());rows.add(row);
        }
        return Map.of("status","OBSERVED","ownerId",player.getUUID(),"agents",rows,"total",all.size(),"nextOffset",(long)offset+rows.size()<all.size()?offset+rows.size():-1);
    }
    static Map<String,Object> settings(ServerPlayer player,UUID agent){
        if(!ServerTaskStart.allowed(player,agent))throw new SecurityException("AGENT_TOOL_PERMISSION");
        var bodies=MineAgentRuntimeServices.bodies(player.level().getServer());var a=definition(player,agent);var body=bodies.body(agent).orElse(null);var result=new LinkedHashMap<String,Object>();
        result.put("status","OBSERVED");result.put("agent_id",agent);result.put("name",a.displayName());result.put("revision",bodies.revision(agent));result.put("gameMode",body==null?"NOT_LOADED":body.gameMode.getGameModeForPlayer().name());result.put("respawn",bodies.respawnPolicy(agent));
        result.put("model",ServerAgentModels.permitted(player,agent)?ServerAgentModels.read(player,agent):Map.of("status","PERMISSION_DENIED"));return result;
    }
    static void aiOnly(ServerPlayer player,UUID agent,String tool,JsonNode args){
        if(args.path("actor").asText("ai").equals("player"))throw new SecurityException("AGENT_TARGET_AI_BODY_ONLY");
        if(Set.of("control_behavior","control_skill","set_combat_policy").contains(tool)){
            var runtime=dev.mineagent.runtime.neoforge.skill.SkillRuntime.get(player.level().getServer());
            var report=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(runtime.snapshot(player,agent));
            for(var row:report.path("skills")){var session=row.path("session");if(session.path("spec").path("id").asText().equals(args.path("id").asText())||session.path("session").asText().equals(args.path("id").asText())){
                if(!session.path("spec").path("actor").asText().equals("ai"))throw new SecurityException("AGENT_TARGET_AI_BODY_ONLY");return;
            }}
            throw new IllegalArgumentException("TARGET_AGENT_SKILL_NOT_OBSERVED");
        }
    }
    private static void exact(JsonNode args,String...fields){if(!args.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(Set.of(fields)))throw new IllegalArgumentException("AGENT_SETTING_FIELDS",new IllegalArgumentException("Expected fields: "+Arrays.toString(fields)));}
    static CompletableFuture<Map<String,Object>> set(ServerPlayer player,UUID agent,UUID operation,JsonNode args,BooleanSupplier permit)throws Exception{
        if(!permit.getAsBoolean()||!ServerTaskStart.allowed(player,agent))throw new SecurityException("AGENT_TOOL_PERMISSION");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();String setting=args.path("setting").asText();Map<String,Object> result;
        switch(setting){
            case "name"->{exact(args,"setting","name","expected_revision");var changed=ServerAgentManagement.write(player,operation,Map.of("kind","rename","agentId",agent.toString(),"name",args.get("name").asText(),"expectedRevision",args.get("expected_revision").asText()));result=Map.of("status","APPLIED","observed",changed);}
            case "game_mode"->{exact(args,"setting","mode","expected_mode");return ServerBehaviorPanel.write(player,agent,operation,"set_game_mode",json.createObjectNode().put("mode",args.get("mode").asText()).put("expected_mode",args.get("expected_mode").asText()).toString(),permit);}
            case "auto_respawn"->{exact(args,"setting","enabled","expected_revision");return ServerBehaviorPanel.write(player,agent,operation,"set_respawn_policy",json.createObjectNode().put("enabled",args.get("enabled").asBoolean()).put("expected_revision",args.get("expected_revision").asLong()).toString(),permit).thenApply(value->Map.of("status","APPLIED","observed",value));}
            case "model"->{exact(args,"setting","model_mode","model","base_url","expected_revision");result=Map.of("status","APPLIED","observed",ServerAgentModels.save(player,Map.of("agentId",agent.toString(),"mode",args.get("model_mode").asText(),"model",args.get("model").asText(),"baseUrl",args.get("base_url").asText(),"expectedRevision",args.get("expected_revision").asText())));}
            default->throw new IllegalArgumentException("AGENT_SETTING_UNKNOWN");
        }
        return CompletableFuture.completedFuture(result);
    }
    static Map<String,Object> stop(ServerPlayer player,UUID agent){boolean changed=dev.mineagent.runtime.neoforge.skill.SkillRuntime.get(player.level().getServer()).stopAll(player,agent);return Map.of("status","STOPPED","agent_id",agent,"changed",changed,"automaticResume",false);}
    private ConversationAgentScope(){}
}
