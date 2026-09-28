package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.core.Holder;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.world.item.alchemy.PotionBrewing$Mix")
public interface PotionMixAccess {
    @Accessor("from") Holder<?> divzero$from();
    @Accessor("ingredient") Ingredient divzero$ingredient();
    @Accessor("to") Holder<?> divzero$to();
}
