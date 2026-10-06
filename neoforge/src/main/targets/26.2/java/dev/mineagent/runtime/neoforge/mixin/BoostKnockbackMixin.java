package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Preserve the existing explicitly enabled non-PvP enhancement on the new native overload. */
@Mixin(LivingEntity.class)
public abstract class BoostKnockbackMixin {
    @Unique private Vec3 divzero$beforeKnockback;
    @Inject(method="knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",at=@At("HEAD"))
    private void divzero$record(double strength,double x,double z,DamageSource source,float damage,boolean flag,CallbackInfo ci){divzero$beforeKnockback=((LivingEntity)(Object)this).getDeltaMovement();}
    @Inject(method="knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",at=@At("RETURN"))
    private void divzero$apply(double strength,double x,double z,DamageSource source,float damage,boolean flag,CallbackInfo ci){var before=divzero$beforeKnockback;divzero$beforeKnockback=null;if(before!=null)dev.mineagent.runtime.neoforge.skill.BoostRuntime.knockback((LivingEntity)(Object)this,before);}
}
