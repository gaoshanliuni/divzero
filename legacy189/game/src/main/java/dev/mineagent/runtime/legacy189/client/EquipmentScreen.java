package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativeOffhand;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;

/** Purpose-specific native inventory window, not a substitute for the F2 workspace. */
public final class EquipmentScreen extends GuiContainer {
    public EquipmentScreen(EntityPlayer player) { super(new NativeOffhand.EquipmentContainer(player)); xSize = 176; ySize = 140; }
    @Override protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xffc6c6c6);
        drawRect(guiLeft + 79, guiTop + 19, guiLeft + 97, guiTop + 37, 0xff555555);
        for (net.minecraft.inventory.Slot slot : inventorySlots.inventorySlots)
            drawRect(guiLeft + slot.xDisplayPosition - 1, guiTop + slot.yDisplayPosition - 1,
                    guiLeft + slot.xDisplayPosition + 17, guiTop + slot.yDisplayPosition + 17, 0xff8b8b8b);
    }
    @Override protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(net.minecraft.client.resources.I18n.format("gui.divzero.offhand"), 8, 6, 0x404040);
        fontRendererObj.drawString(net.minecraft.client.resources.I18n.format("container.inventory"), 8, 39, 0x404040);
    }
}
