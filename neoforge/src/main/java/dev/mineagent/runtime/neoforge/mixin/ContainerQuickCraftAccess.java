package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(AbstractContainerMenu.class)
public interface ContainerQuickCraftAccess {
    @Invoker("resetQuickCraft") void mineagent$resetQuickCraft();
}
