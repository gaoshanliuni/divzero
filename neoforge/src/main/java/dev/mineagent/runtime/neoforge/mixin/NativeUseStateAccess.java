package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LivingEntity.class)
public interface NativeUseStateAccess {
    @Accessor("useItem") void divzero$useItem(ItemStack stack);
    @Accessor("useItemRemaining") void divzero$useRemaining(int ticks);
}
