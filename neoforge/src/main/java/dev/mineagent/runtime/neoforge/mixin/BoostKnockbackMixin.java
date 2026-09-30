package dev.mineagent.runtime.neoforge.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class BoostKnockbackMixin {
    @Unique private Vec3 divzero$beforeKnockback;
    @Inject(method="knockback",at=@At("HEAD"))
    private void divzero$recordImpulse(double strength,double x,double z,CallbackInfo ci){divzero$beforeKnockback=((LivingEntity)(Object)this).getDeltaMovement();}
    @Inject(method="knockback",at=@At("RETURN"))
    private void divzero$applyAuthorizedRatio(double strength,double x,double z,CallbackInfo ci){var before=divzero$beforeKnockback;divzero$beforeKnockback=null;if(before!=null)dev.mineagent.runtime.neoforge.skill.BoostRuntime.knockback((LivingEntity)(Object)this,before);}
}
