package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativeFixture;
import dev.mineagent.runtime.legacy189.NativeOffhand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import java.io.File;

/** Isolated native GUI input probe. No keyboard/mouse input is sent to another application. */
public final class NativeClientFixture {
    private static int ticks, click, lastClick, finishedAt;
    private static boolean launched;
    private NativeClientFixture() { }
    public static void tick() {
        if (!NativeFixture.requested()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!new File(mc.mcDataDir, "divzero-native-fixture-allow").isFile()) return;
        ticks++;
        if (!launched && ticks > 40 && mc.currentScreen instanceof GuiMainMenu) {
            launched = true;
            WorldSettings settings = new WorldSettings(189L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT).enableCommands();
            mc.launchIntegratedServer(NativeFixture.WORLD, NativeFixture.WORLD, settings);
        }
        if (mc.thePlayer != null && mc.currentScreen instanceof EquipmentScreen && mc.thePlayer.openContainer instanceof NativeOffhand.EquipmentContainer
                && !NativeFixture.finished && ticks - lastClick > 15) {
            // Pick up the actual offhand stack, put it in slot 9, then Shift-click it back.
            int slot = click == 0 ? 0 : 1, mode = click == 2 ? 1 : 0;
            if (click < 3) {
                mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, slot, 0, mode, mc.thePlayer);
                click++; lastClick = ticks;
            } else if (ticks - lastClick > 20) NativeFixture.equipmentClicks = true;
        }
        if (NativeFixture.finished) {
            if (finishedAt == 0) {
                finishedAt = ticks;
                if (mc.thePlayer != null && NativeFixture.successful && !new File(mc.mcDataDir, "divzero-import/pvp-arena-transfer.json").isFile()) mc.thePlayer.sendChatMessage("/ai offhand");
            }
            if (ticks - finishedAt == 30) ScreenShotHelper.saveScreenshot(mc.mcDataDir, Boolean.getBoolean("divzero.legacyFixtureResume") ? "legacy189-resume.png" : "legacy189-native.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (ticks - finishedAt > 50) mc.shutdown();
        }
    }
}
