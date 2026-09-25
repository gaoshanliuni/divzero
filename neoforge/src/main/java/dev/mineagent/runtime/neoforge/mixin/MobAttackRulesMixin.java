package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.*;import net.minecraft.server.level.ServerLevel;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Mob.class)
public abstract class MobAttackRulesMixin {
 @Inject(method="doHurtTarget",at=@At("HEAD"),cancellable=true) private void mineagent$attack(ServerLevel level,Entity target,CallbackInfoReturnable<Boolean> ci){if(dev.mineagent.runtime.neoforge.ui.ServerEntityInterop.preventAttack((Entity)(Object)this))ci.setReturnValue(false);}
}
