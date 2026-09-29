package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Creeper.class)
public interface CombatCreeperAccess {
    @Accessor("swell") int divzero$swell();
    @Accessor("maxSwell") int divzero$maxSwell();
    @Accessor("explosionRadius") int divzero$explosionRadius();
}
