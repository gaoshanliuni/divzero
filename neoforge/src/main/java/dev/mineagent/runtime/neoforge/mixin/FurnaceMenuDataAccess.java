package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(AbstractFurnaceMenu.class)
public interface FurnaceMenuDataAccess {@Accessor("data") ContainerData divzero$data();}
