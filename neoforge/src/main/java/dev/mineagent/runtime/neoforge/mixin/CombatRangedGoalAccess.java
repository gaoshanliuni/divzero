package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RangedAttackGoal.class)
public interface CombatRangedGoalAccess {
    @Accessor("attackTime") int divzero$attackCooldown();
    @Accessor("attackRadius") float divzero$attackRadius();
}
