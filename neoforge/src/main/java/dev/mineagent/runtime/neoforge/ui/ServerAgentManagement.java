package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.agent.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Trusted-shell facade over the existing persistent Agent/body services. No actor authority from the page. */
public final class ServerAgentManagement {
    private ServerAgentManagement(){}
    private static boolean op(ServerPlayer viewer){return viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}
    public static void authorize(ServerPlayer viewer,Map<String,String> args){
        var server=viewer.level().getServer();if(viewer instanceof MineAgentPlayer)throw new SecurityException("AGENT_VIEWER_REQUIRED");
        if("create".equals(args.get("kind"))){if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),op(viewer),PermissionAction.CREATE_AGENT))throw new SecurityException("AGENT_CREATE_DENIED");return;}
        var a=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().toString().equals(args.get("agentId"))).findFirst().orElse(null);
        if(a!=null&&!a.ownerPlayerId().equals(viewer.getUUID())&&(!op(viewer)||"collaborator".equals(args.get("kind"))))throw new SecurityException("AGENT_OWNER_REQUIRED");
    }
    public static Map<String,Object> view(ServerPlayer viewer){
        var server=viewer.level().getServer();var bodies=MineAgentRuntimeServices.bodies(server);var rows=new ArrayList<Map<String,Object>>();
        for(var a:bodies.definitions()){
            var body=bodies.body(a.agentId()).orElse(null);boolean own=a.ownerPlayerId().equals(viewer.getUUID());var row=new LinkedHashMap<String,Object>();
            row.put("id",a.agentId());row.put("name",a.displayName());row.put("revision",bodies.revision(a.agentId()));row.put("requestedMode",a.mode());row.put("bodyState",bodies.bodyState(a.agentId()));row.put("effectiveMode",body==null?"":body.gameMode.getGameModeForPlayer().getName());
            row.put("canManage",own||op(viewer));row.put("canCollaborate",own);row.put("mine",own);row.put("canStartTask",ServerTaskStart.allowed(viewer,a.agentId())&&body!=null&&body.canAct());row.put("health",body==null?null:body.getHealth());row.put("food",body==null?null:body.getFoodData().getFoodLevel());
            row.put("collaborators",own?a.collaboratorPlayerIds().stream().map(UUID::toString).sorted().toList():List.of());rows.add(row);
            row.put("ticketState",bodies.ticketState(a.agentId()));
        }
        return Map.of("agents",rows,"maximum",MineAgentRuntimeServices.config(server).resourceLimits().maxAgents(),"canCreate",MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),op(viewer),PermissionAction.CREATE_AGENT),"players",server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).map(p->Map.of("id",p.getUUID(),"name",p.getGameProfile().name())).toList());
    }
    public static Map<String,String> write(ServerPlayer viewer,UUID operation,Map<String,String> args)throws Exception{
        var server=viewer.level().getServer();if(!server.isSameThread()||viewer instanceof MineAgentPlayer)throw new SecurityException("AGENT_VIEWER_REQUIRED");
        var bodies=MineAgentRuntimeServices.bodies(server);String kind=Objects.requireNonNull(args.get("kind"));
        Set<String> expected=switch(kind){case "create"->Set.of("kind","name","mode");case "rename"->Set.of("kind","agentId","expectedRevision","name");case "mode"->Set.of("kind","agentId","expectedRevision","mode","confirmed");case "delete"->Set.of("kind","agentId","expectedRevision","confirmed");case "collaborator"->Set.of("kind","agentId","expectedRevision","playerId","enabled");default->throw new IllegalArgumentException("AGENT_ACTION_INVALID");};
        if(!args.keySet().equals(expected))throw new IllegalArgumentException("AGENT_ARGUMENTS_INVALID");
        if(kind.equals("create")){
            if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),op(viewer),PermissionAction.CREATE_AGENT))throw new SecurityException("AGENT_CREATE_DENIED");
            AgentDefinition a;
            try{a=bodies.createIdempotent(operation,args.get("name"),viewer,AgentMode.valueOf(args.get("mode")));}
            catch(dev.mineagent.runtime.core.agent.AgentLimitException limit){throw new IllegalStateException("AGENT_LIMIT");}
            catch(IllegalArgumentException invalid){throw new IllegalStateException("AI 玩家名称已存在".equals(invalid.getMessage())?"AGENT_NAME_EXISTS":"AGENT_OPERATION_REUSED".equals(invalid.getMessage())?"AGENT_OPERATION_REUSED":"AGENT_NAME_INVALID");}
            var permissions=MineAgentRuntimeServices.permissions(server);var registered=permissions.owner(a.agentId());
            if(registered.isEmpty())permissions.registerOwnership(a.agentId(),a.ownerPlayerId());else if(!registered.get().equals(a.ownerPlayerId()))throw new SecurityException("AGENT_OWNER_CONFLICT");
            return Map.of("agentId",a.agentId().toString(),"revision",Long.toString(bodies.revision(a.agentId())),"bodyState",bodies.bodyState(a.agentId()));
        }
        UUID id=UUID.fromString(args.get("agentId"));var a=bodies.definitions().stream().filter(d->d.agentId().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("AGENT_NOT_FOUND"));
        if(!a.ownerPlayerId().equals(viewer.getUUID())&&(!op(viewer)||kind.equals("collaborator")))throw new SecurityException("AGENT_OWNER_REQUIRED");
        if(bodies.revision(id)!=Long.parseLong(args.get("expectedRevision")))throw new IllegalStateException("STALE_AGENT_REVISION");
        if(kind.equals("rename")){
            String name=args.get("name");if(name==null||name.isBlank()||name.strip().codePointCount(0,name.strip().length())>32||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalStateException("AGENT_NAME_INVALID");
            if(bodies.definitions().stream().anyMatch(d->!d.agentId().equals(id)&&d.displayName().toLowerCase(Locale.ROOT).equals(name.strip().toLowerCase(Locale.ROOT))))throw new IllegalStateException("AGENT_NAME_EXISTS");
        }
        if((kind.equals("mode")||kind.equals("delete"))&&!"true".equals(args.get("confirmed")))throw new IllegalArgumentException("AGENT_CONFIRM_REQUIRED");
        boolean accepted=switch(kind){
            case "rename"->bodies.rename(id,viewer.getUUID(),op(viewer),args.get("name"));
            case "mode"->bodies.setMode(id,viewer.getUUID(),op(viewer),AgentMode.valueOf(args.get("mode")));
            case "delete"->bodies.remove(id,viewer.getUUID(),op(viewer));
            case "collaborator"->{if(!Set.of("true","false").contains(args.get("enabled")))throw new IllegalArgumentException("AGENT_ARGUMENTS_INVALID");yield bodies.setCollaborator(id,viewer.getUUID(),UUID.fromString(args.get("playerId")),Boolean.parseBoolean(args.get("enabled")));}
            default->false;
        };
        if(!accepted)throw new IllegalStateException("AGENT_MUTATION_REJECTED");
        if(kind.equals("delete"))MineAgentRuntimeServices.permissions(server).removeOwnership(id);
        return Map.of("agentId",id.toString(),"state",kind.equals("delete")?"REMOVED":"UPDATED","revision",Long.toString(bodies.revision(id)));
    }
}
