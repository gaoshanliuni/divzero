package dev.mineagent.runtime.neoforge.task;

import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** One authoritative action policy for native UI, conversation tools and delayed world work. */
public final class ServerAuthority {
    public static boolean allowed(ServerPlayer player, PermissionAction action) {
        var server=player.level().getServer();
        return server.isSameThread() && server.getPlayerList().getPlayer(player.getUUID())==player
                && MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),
                player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),action);
    }
    /** Persistent server-side rules keep their owner; disconnect never becomes implicit authorization. */
    public static boolean allowed(net.minecraft.server.MinecraftServer server,java.util.UUID owner,PermissionAction action) {
        if(!server.isSameThread()||owner==null)return false;
        var player=server.getPlayerList().getPlayer(owner);
        boolean operator=player!=null?player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                :server.getProfilePermissions(new net.minecraft.server.players.NameAndId(owner,"DivZeroOwner")).hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return MineAgentRuntimeServices.permissions(server).allowed(owner,operator,action);
    }
    public static void require(ServerPlayer player, PermissionAction action) {
        if(!allowed(player,action))throw new SecurityException("PERMISSION_REQUIRED_"+action.name());
    }
    private ServerAuthority() {}
}
