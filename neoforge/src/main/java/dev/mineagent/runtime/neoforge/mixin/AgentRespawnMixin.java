package dev.mineagent.runtime.neoforge.mixin;
import com.mojang.authlib.GameProfile;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Keep the specialized body while retaining vanilla/NeoForge respawn transfer, position, events and tracking. */
@Mixin(PlayerList.class)
public abstract class AgentRespawnMixin {
    @Redirect(method="respawn",at=@At(value="NEW",target="net/minecraft/server/level/ServerPlayer"),require=1)
    private ServerPlayer mineagent$newBody(MinecraftServer server,ServerLevel level,GameProfile profile,ClientInformation info,
                                           ServerPlayer old,boolean keepAll,Entity.RemovalReason reason){
        if(old instanceof MineAgentPlayer body)return body.replacementForRespawn(server,level,profile,info);
        return new ServerPlayer(server,level,profile,info);
    }
}
