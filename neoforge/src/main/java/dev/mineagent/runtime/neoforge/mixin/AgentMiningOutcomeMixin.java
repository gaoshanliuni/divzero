package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Record the native removal result, not merely START/STOP acceptance or a later unrelated air block. */
@Mixin(ServerPlayerGameMode.class)
public abstract class AgentMiningOutcomeMixin {
    @Shadow @Final protected ServerPlayer player;
    @Inject(method="removeBlock",at=@At("HEAD"),cancellable=true)
    private void mineagent$guard(BlockPos pos,BlockState state,boolean canHarvest,ItemStack tool,CallbackInfoReturnable<Boolean> result){if(player instanceof MineAgentPlayer body&&(!body.validateTaskControl()||!body.validateMiningState(pos)))result.setReturnValue(false);}
    @Inject(method="removeBlock",at=@At("RETURN"))
    private void mineagent$removed(BlockPos pos,BlockState state,boolean canHarvest,ItemStack tool,CallbackInfoReturnable<Boolean> result){if(player instanceof MineAgentPlayer body)body.nativeMiningResult(pos,state,tool,result.getReturnValueZ());}
    @Inject(method="destroyBlock",at=@At("RETURN"))
    private void mineagent$rejected(BlockPos pos,CallbackInfoReturnable<Boolean> result){if(!result.getReturnValueZ()&&player instanceof MineAgentPlayer body)body.nativeMiningRejected(pos);}
}
