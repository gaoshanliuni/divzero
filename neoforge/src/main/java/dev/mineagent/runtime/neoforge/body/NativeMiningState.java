package dev.mineagent.runtime.neoforge.body;
import dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess;
import net.minecraft.core.BlockPos;
public final class NativeMiningState {
    private NativeMiningState(){}
    public static String afterState(boolean loaded,java.util.function.Supplier<String> read){return loaded?read.get():"UNLOADED";}
    public static boolean tracking(AgentMiningAccess access,BlockPos pos){return access.mineagent$isDestroying()&&pos.equals(access.mineagent$destroyPos())||access.mineagent$hasDelayed()&&pos.equals(access.mineagent$delayedPos());}
    public static void cancel(AgentMiningAccess access,BlockPos pos){if(pos==null)return;if(pos.equals(access.mineagent$destroyPos()))access.mineagent$setDestroying(false);if(pos.equals(access.mineagent$delayedPos()))access.mineagent$setDelayed(false);}
}
