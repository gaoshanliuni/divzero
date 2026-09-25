package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.Entity;import net.minecraft.server.level.ServerLevel;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Optional adapter: intercept before Naga's shield side effects, not only the later damage event. */
@Pseudo @Mixin(targets="twilightforest.entity.boss.Naga",remap=false)
public abstract class TwilightNagaAttackMixin {
 @Inject(method="doHurtTarget",at=@At("HEAD"),cancellable=true,remap=false) private void mineagent$attack(ServerLevel level,Entity target,CallbackInfoReturnable<Boolean> ci){if(dev.mineagent.runtime.neoforge.ui.ServerEntityInterop.preventAttack((Entity)(Object)this))ci.setReturnValue(false);}
}
