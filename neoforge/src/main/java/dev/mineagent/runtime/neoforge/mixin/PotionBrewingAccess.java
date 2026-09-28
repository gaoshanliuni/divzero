package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
@Mixin(PotionBrewing.class)
public interface PotionBrewingAccess {
    @Accessor("potionMixes") List<?> divzero$potionMixes();
    @Accessor("containerMixes") List<?> divzero$containerMixes();
}
