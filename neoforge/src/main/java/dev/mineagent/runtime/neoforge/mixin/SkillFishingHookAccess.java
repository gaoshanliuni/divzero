package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Read only; fishing decisions observe the actual vanilla bite window. */
@Mixin(FishingHook.class)
public interface SkillFishingHookAccess {
    @Accessor("nibble") int divzero$skillNibble();
}
