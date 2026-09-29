package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RangedBowAttackGoal.class)
public interface CombatBowGoalAccess {
    @Accessor("attackTime") int divzero$attackCooldown();
    @Accessor("attackRadiusSqr") float divzero$attackRadiusSquared();
}
