package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.server.level.*;import net.minecraft.world.*;import net.minecraft.world.item.ItemStack;import net.minecraft.world.level.Level;import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-call hand consumption is independent of unrelated item pickups in the same inventory tick. */
@Mixin(ServerPlayerGameMode.class)
public abstract class SkillItemUseOutcomeMixin {
    @Unique private final java.util.Deque<java.util.Map.Entry<Boolean,ItemStack>> divzero$skillUses=new java.util.ArrayDeque<>();
    @Inject(method="useItemOn",at=@At("HEAD"))
    private void divzero$beforeSkillUse(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> result){divzero$skillUses.push(new java.util.AbstractMap.SimpleImmutableEntry<>(dev.mineagent.runtime.neoforge.skill.SkillRuntime.observingUse(player,hit.getBlockPos()),stack.copy()));}
    @Inject(method="useItemOn",at=@At("RETURN"))
    private void divzero$afterSkillUse(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> result){if(divzero$skillUses.isEmpty())return;var before=divzero$skillUses.pop();if(before.getKey())dev.mineagent.runtime.neoforge.skill.SkillRuntime.nativeUse(player,hit.getBlockPos(),before.getValue(),stack,result.getReturnValue()!=null&&result.getReturnValue().consumesAction());}
}
