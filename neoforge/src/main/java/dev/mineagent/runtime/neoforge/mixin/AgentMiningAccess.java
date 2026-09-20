package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerPlayerGameMode.class)
public interface AgentMiningAccess {
    @Accessor("isDestroyingBlock") boolean mineagent$isDestroying();
    @Accessor("hasDelayedDestroy") boolean mineagent$hasDelayed();
    @Accessor("destroyPos") BlockPos mineagent$destroyPos();
    @Accessor("delayedDestroyPos") BlockPos mineagent$delayedPos();
    @Accessor("isDestroyingBlock") void mineagent$setDestroying(boolean value);
    @Accessor("hasDelayedDestroy") void mineagent$setDelayed(boolean value);
}
