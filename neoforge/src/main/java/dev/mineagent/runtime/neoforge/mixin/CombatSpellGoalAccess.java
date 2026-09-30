package dev.mineagent.runtime.neoforge.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(targets="net.minecraft.world.entity.monster.illager.SpellcasterIllager$SpellcasterUseSpellGoal")
public interface CombatSpellGoalAccess {
    @Accessor("attackWarmupDelay") int divzero$warmup();
    @Accessor("nextAttackTickCount") int divzero$nextCastTick();
    @Invoker("getCastWarmupTime") int divzero$warmupDuration();
}
