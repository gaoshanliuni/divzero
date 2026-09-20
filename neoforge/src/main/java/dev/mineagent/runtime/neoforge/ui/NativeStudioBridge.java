package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native UI transport to the same server-owned Coder service, independent of a Chromium Session. */
public final class NativeStudioBridge {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NativeStudioBridge(){}
    public static void handle(MineAgentPayloads.NativeStudioRequest request,ServerPlayer player){
        String world="";
        try{
            ServerJavaStudio.authorize(player);var server=player.level().getServer();world=MineAgentRuntimeServices.worldId(server).toString();
            var args=request.arguments();String kind=args.getOrDefault("kind","");Map<String,Object> result;
            if(!request.write()&&kind.equals("init")){
                if(!args.keySet().equals(Set.of("kind","offset"))||!request.worldId().isEmpty()&&!world.equals(request.worldId()))throw new IllegalArgumentException("STUDIO_NATIVE_CONTEXT_CHANGED");
                int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>100000)throw new IllegalArgumentException("STUDIO_NATIVE_PAGE");
                boolean operator=player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);var permissions=MineAgentRuntimeServices.permissions(server);
                var all=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->permissions.canMutateAgent(a,player.getUUID(),operator)).toList();
                var page=all.stream().skip(offset).limit(8).map(a->Map.of("id",a.agentId().toString(),"name",a.displayName())).toList();
                String nativeSnapshot="";try{nativeSnapshot=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest().hash();}catch(Exception notCaptured){}
                result=Map.of("worldId",world,"agents",page,"nextOffset",offset+page.size(),"more",offset+page.size()<all.size(),"startAllowed",permissions.allowed(player.getUUID(),operator,PermissionAction.START_TASK),"nativeSnapshot",nativeSnapshot);
            }else{
                if(!world.equals(request.worldId()))throw new IllegalStateException("STUDIO_NATIVE_CONTEXT_CHANGED");
                if(request.write()){
                    if(!Set.of("coderSubmit","coderRepair","coderCancel","coderAdopt","create","save","publishSource","run","stop","stopLegacyScript","workspaceFile","dependency").contains(args.getOrDefault("action","")))throw new IllegalArgumentException("STUDIO_NATIVE_ACTION");
                    result=ServerJavaStudio.write(player,request.operationId(),args);
                }else{
                    if(!Set.of("coderList","coderGet","coderText","coderFiles","coderFile","list","get","source","package","draftHistory","historySource","diagnostics","scriptDiag","scriptCheck","workspaceFiles","workspaceSource","workspaceHistory","dependencies","dependencyCandidates").contains(kind))throw new IllegalArgumentException("STUDIO_NATIVE_ACTION");
                    result=ServerJavaStudio.read(player,args);
                }
            }
            String state=JSON.writeValueAsString(result);if(state.getBytes(StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("STUDIO_NATIVE_RESPONSE_LIMIT");
            send(player,new MineAgentPayloads.NativeStudioResponse(request.requestId(),world,request.write()?"APPLIED":"OBSERVED",state));
        }catch(Exception e){
            send(player,new MineAgentPayloads.NativeStudioResponse(request.requestId(),world,ServerJavaStudio.error(e),""));
        }
    }
    private static void send(ServerPlayer player,MineAgentPayloads.NativeStudioResponse response){
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,response);
    }
}
