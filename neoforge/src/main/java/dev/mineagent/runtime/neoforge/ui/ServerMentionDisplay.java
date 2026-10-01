package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.core.config.AgentMentionDisplay;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class ServerMentionDisplay {
    public static AgentMentionDisplay.Setting read(MinecraftServer server,UUID agent){return AgentMentionDisplay.read(MineAgentRuntimeServices.config(server).snapshot().values(),MineAgentRuntimeServices.worldId(server),agent);}
    public static boolean shared(MinecraftServer server,UUID agent){return AgentMentionDisplay.publicReply(read(server,agent),true);}
    public static void created(MinecraftServer server,UUID agent){AgentMentionDisplay.create(MineAgentRuntimeServices.config(server),MineAgentRuntimeServices.worldId(server),agent);}
    public static Map<String,String> write(ServerPlayer viewer,Map<String,String> args){
        if(!args.keySet().equals(Set.of("kind","agentId","displayRevision","mode")))throw new IllegalArgumentException("AGENT_ARGUMENTS_INVALID");
        UUID agent=UUID.fromString(args.get("agentId"));var server=viewer.level().getServer();var definition=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElseThrow(()->new IllegalArgumentException("AGENT_NOT_FOUND"));
        if(!definition.ownerPlayerId().equals(viewer.getUUID())&&!viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("AGENT_OWNER_REQUIRED");
        var result=AgentMentionDisplay.save(MineAgentRuntimeServices.config(server),MineAgentRuntimeServices.worldId(server),agent,Long.parseLong(args.get("displayRevision")),AgentMentionDisplay.Mode.valueOf(args.get("mode")));
        return Map.of("mode",result.mode().name(),"displayRevision",Long.toString(result.revision()));
    }
    private ServerMentionDisplay(){}
}
