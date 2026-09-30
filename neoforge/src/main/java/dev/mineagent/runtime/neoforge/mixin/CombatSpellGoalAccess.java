package dev.mineagent.runtime.neoforge.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.world.entity.monster.illager.SpellcasterIllager$SpellcasterUseSpellGoal")
public interface CombatSpellGoalAccess {
    @Accessor("attackWarmupDelay") int divzero$warmup();
    @Accessor("nextAttackTickCount") int divzero$nextCastTick();
}
