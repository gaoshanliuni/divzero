package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Player.class)
public interface CombatCriticalAccess {
    @Invoker("canCriticalAttack") boolean divzero$canCriticalAttack(Entity target);
}
