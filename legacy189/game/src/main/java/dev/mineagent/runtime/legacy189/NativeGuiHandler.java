package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

public class NativeGuiHandler implements IGuiHandler {
    @Override public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return id == 0 && player.isEntityAlive() && !player.isSpectator() ? new NativeOffhand.EquipmentContainer(player) : null;
    }
    @Override public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return LegacyMod.proxy.equipmentScreen(id, player);
    }
}
