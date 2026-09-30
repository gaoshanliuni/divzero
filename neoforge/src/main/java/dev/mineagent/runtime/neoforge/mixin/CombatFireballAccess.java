package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LargeFireball.class)
public interface CombatFireballAccess {
    @Accessor("explosionPower") int divzero$explosionPower();
}
