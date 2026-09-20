package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** NBT-load setter without pre-connection network traffic, as used by ServerPlayer itself. */
@Mixin(ServerPlayerGameMode.class)
public interface AgentGameModeAccess {
    @Invoker("setGameModeForPlayer") void mineagent$restoreGameMode(GameType mode,GameType previous);
}
