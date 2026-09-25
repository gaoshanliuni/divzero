package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.Entity;import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo @Mixin(targets="twilightforest.entity.boss.NagaSegment",remap=false)
public abstract class TwilightNagaSegmentAttackMixin {
 @Inject(method="collideWithEntity",at=@At("HEAD"),cancellable=true,remap=false) private void mineagent$contact(Entity target,CallbackInfo ci){if(dev.mineagent.runtime.neoforge.ui.ServerEntityInterop.preventAttack(dev.mineagent.runtime.neoforge.ui.ServerEntityInterop.root((Entity)(Object)this)))ci.cancel();}
}
