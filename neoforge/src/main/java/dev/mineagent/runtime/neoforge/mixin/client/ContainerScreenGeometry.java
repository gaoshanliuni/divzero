package dev.mineagent.runtime.neoforge.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenGeometry {
    @Accessor("leftPos") int divzero$left();
    @Accessor("topPos") int divzero$top();
    @Accessor("imageWidth") int divzero$width();
    @Accessor("imageHeight") int divzero$height();
}
