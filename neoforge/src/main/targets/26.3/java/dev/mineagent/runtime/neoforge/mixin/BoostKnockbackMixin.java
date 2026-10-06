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
    // The five-argument overload delegates here. Observe/apply the existing opt-in feature only once.
    @Inject(method="knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",at=@At("HEAD"))
    private void divzero$recordImpulse(CallbackInfo ci){divzero$beforeKnockback=((LivingEntity)(Object)this).getDeltaMovement();}
    @Inject(method="knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",at=@At("RETURN"))
    private void divzero$applyAuthorizedRatio(CallbackInfo ci){var before=divzero$beforeKnockback;divzero$beforeKnockback=null;if(before!=null)dev.mineagent.runtime.neoforge.skill.BoostRuntime.knockback((LivingEntity)(Object)this,before);}
}
