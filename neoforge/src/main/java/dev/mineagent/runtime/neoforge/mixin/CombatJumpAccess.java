package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(LivingEntity.class)
public interface CombatJumpAccess {
    @Invoker("getJumpPower") float divzero$jumpPower();
}
