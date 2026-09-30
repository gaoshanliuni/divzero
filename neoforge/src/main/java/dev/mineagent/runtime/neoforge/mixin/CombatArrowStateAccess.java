package dev.mineagent.runtime.neoforge.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractArrow.class)
public interface CombatArrowStateAccess {
    @Invoker("isInGround") boolean divzero$inGround();
    @Invoker("getWaterInertia") float divzero$waterInertia();
}
