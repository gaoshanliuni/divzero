package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(MeleeAttackGoal.class)
public interface CombatMeleeGoalAccess {
    @Accessor("ticksUntilNextAttack") int divzero$attackCooldown();
}
