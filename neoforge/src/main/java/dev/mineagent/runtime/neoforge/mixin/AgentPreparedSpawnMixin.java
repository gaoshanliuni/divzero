package dev.mineagent.runtime.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import dev.mineagent.runtime.neoforge.body.MineAgentConnection;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Reuse native async spawn preparation/NBT/events/vehicle loading. Ordinary connections are unchanged. */
@Mixin(targets="net.minecraft.server.network.config.PrepareSpawnTask$Ready")
public abstract class AgentPreparedSpawnMixin {
    @Redirect(method="spawn",at=@At(value="NEW",target="net/minecraft/server/level/ServerPlayer"),require=1)
    private ServerPlayer mineagent$preparedBody(MinecraftServer server,ServerLevel level,GameProfile profile,
                                               ClientInformation information,Connection connection,CommonListenerCookie cookie){
        if(connection instanceof MineAgentConnection agent)return agent.createPreparedPlayer(server,level,profile,information);
        return new ServerPlayer(server,level,profile,information);
    }
}
