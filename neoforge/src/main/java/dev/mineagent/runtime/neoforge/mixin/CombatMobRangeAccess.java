package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;
@Mixin(Mob.class)
public interface CombatMobRangeAccess {
    @Accessor("DEFAULT_ATTACK_REACH") static double divzero$defaultReach(){throw new AssertionError();}
    @Invoker("getAttackBoundingBox") AABB divzero$attackBox(double expansion);
}
