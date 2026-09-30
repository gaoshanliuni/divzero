package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.projectile.EvokerFangs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(EvokerFangs.class)
public interface CombatFangsAccess {
    @Accessor("warmupDelayTicks") int divzero$warmupTicks();
    @Accessor("lifeTicks") int divzero$lifeTicks();
}
