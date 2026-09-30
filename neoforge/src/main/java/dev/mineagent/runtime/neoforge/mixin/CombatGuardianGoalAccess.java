package dev.mineagent.runtime.neoforge.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.world.entity.monster.Guardian$GuardianAttackGoal")
public interface CombatGuardianGoalAccess {
    @Accessor("attackTime") int divzero$attackTime();
}
