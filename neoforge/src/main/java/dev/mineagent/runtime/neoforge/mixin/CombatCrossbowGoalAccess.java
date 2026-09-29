package dev.mineagent.runtime.neoforge.mixin;

import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RangedCrossbowAttackGoal.class)
public interface CombatCrossbowGoalAccess {
    @Accessor("attackDelay") int divzero$attackDelay();
    @Accessor("attackRadiusSqr") float divzero$attackRadiusSquared();
}
