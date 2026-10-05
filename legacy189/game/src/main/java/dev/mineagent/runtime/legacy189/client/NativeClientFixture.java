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
    private static int instructionDone;
    private static boolean respawnRequested, loadoutCaptured;
    private static boolean disconnected;
    private static int packedStep;
    private static int foodAt;
    private static boolean foodChosen;
    private NativeClientFixture() { }
    public static void tick() {
        if (!NativeFixture.requested()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!new File(mc.mcDataDir, "divzero-native-fixture-allow").isFile()) return;
        ticks++;
        if(Boolean.getBoolean("divzero.legacyAdvancedFixture")){
            if(NativeFixture.finished)dev.mineagent.runtime.legacy189.NativeAdvancedVerification.counterAttack=false;
            AdvancedClientFixture.tick();
        }
        if(Boolean.getBoolean("divzero.legacyTrainingFixture")&&NativeFixture.trainingReady&&!NativeFixture.trainingUiClicked&&mc.currentScreen instanceof DuelClient.LoadoutScreen){
            try{DuelClient.LoadoutScreen screen=(DuelClient.LoadoutScreen)mc.currentScreen;if(!screen.fixtureLayoutFits())throw new IllegalStateException("TRAINING_MENU_CLIPPED");screen.fixtureSelect(33);NativeFixture.trainingUiClicked=true;screen.fixtureSelect(32);}catch(Exception failure){throw new IllegalStateException("TRAINING_UI_INPUT",failure);}
        }
        if (Boolean.getBoolean("divzero.legacyComboFixture")) {
            if (NativeFixture.finished) dev.mineagent.runtime.legacy189.NativeComboVerification.clientMode = -1;
            ComboClientFixture.tick();
        }
        if (!launched && ticks > 40 && mc.currentScreen instanceof GuiMainMenu) {
            launched = true;
            WorldSettings settings = new WorldSettings(189L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT).enableCommands();
            String world = Boolean.getBoolean("divzero.legacyPackedFixture") ? NativeFixture.PACKED_WORLD : NativeFixture.WORLD;
            mc.launchIntegratedServer(world, world, settings);
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
                if (mc.thePlayer != null && NativeFixture.successful && !Boolean.getBoolean("divzero.legacyPackedFixture") && !new File(mc.mcDataDir, "divzero-import/pvp-arena-transfer.json").isFile()) mc.thePlayer.sendChatMessage("/ai offhand");
                else mc.displayGuiScreen(null);
            }
            if (ticks - finishedAt == 30) ScreenShotHelper.saveScreenshot(mc.mcDataDir, Boolean.getBoolean("divzero.legacyFixtureResume") ? "legacy189-resume.png" : "legacy189-native.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (ticks - finishedAt > 50 && !disconnected) {
                disconnected = true;
                if (mc.theWorld != null) mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld(null); mc.displayGuiScreen(new GuiMainMenu());
            }
            if (disconnected && ticks - finishedAt > 90) mc.shutdown();
        }
        if (Boolean.getBoolean("divzero.legacyPackedFixture") && NativeFixture.packedChecked && !NativeFixture.finished) {
            try {
                if (!Boolean.getBoolean("divzero.legacyComboFixture") && DuelClient.state() != null && DuelClient.state().get("rounds").getAsInt() == 0 && DuelClient.state().get("phase").getAsString().equals("COUNTDOWN") && !NativeFixture.packedFoodUsed) {
                    if (foodAt == 0) {
                        foodAt = ticks; mc.thePlayer.inventory.currentItem = 2;
                        net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                        net.minecraft.client.settings.KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
                    } else if (ticks - foodAt >= 40) {
                        net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                        mc.thePlayer.inventory.currentItem = 0; NativeFixture.packedFoodUsed = true;
                    }
                }
                if (mc.thePlayer != null && !mc.thePlayer.isEntityAlive()) {
                    if (!respawnRequested) { mc.thePlayer.respawnPlayer(); respawnRequested = true; }
                    return;
                }
                respawnRequested = false;
                if (packedStep == 0 && mc.currentScreen instanceof DuelClient.LoadoutScreen) {
                    mc.displayGuiScreen(null); packedKey();
                    if (!(mc.currentScreen instanceof DuelClient.LoadoutScreen)) throw new IllegalStateException("PACKED_MENU_KEY");
                    mc.displayGuiScreen(new PauseProbe()); packedStep++;
                } else if (packedStep == 1 && mc.currentScreen instanceof PauseProbe) {
                    ((PauseProbe) mc.currentScreen).clickDuel();
                    if (!(mc.currentScreen instanceof DuelClient.LoadoutScreen)) throw new IllegalStateException("PACKED_PAUSE_MENU");
                    NativeFixture.packedMenuRecovered = true; packedStep++;
                } else if (packedStep == 2 && mc.currentScreen instanceof DuelClient.LoadoutScreen) {
                    if (!Boolean.getBoolean("divzero.legacyComboFixture") && !DuelClient.state().getAsJsonArray("human").get(5).getAsString().equals("minecraft:golden_apple")) {
                        if (!foodChosen) foodChosen = DuelClient.fixtureChoose(0, 5, "minecraft:golden_apple");
                        return;
                    }
                    DuelClient.LoadoutScreen screen = (DuelClient.LoadoutScreen) mc.currentScreen;
                    if (!screen.fixtureLayoutFits()) throw new IllegalStateException("PACKED_LOADOUT_CLIPPED");
                    NativeFixture.packedGuiScale = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
                    ScreenShotHelper.saveScreenshot(mc.mcDataDir, "legacy189-packed-scale.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    screen.fixtureSelect(30); NativeFixture.packedUiClicked = true; packedStep++;
                } else if (packedStep == 3 && NativeFixture.packedNextRound && mc.currentScreen instanceof DuelClient.LoadoutScreen
                        && DuelClient.state().get("phase").getAsString().equals("READY")) {
                    ((DuelClient.LoadoutScreen) mc.currentScreen).fixtureSelect(30); packedStep++;
                } else if (packedStep == 4 && NativeFixture.packedStopAllowed && DuelClient.state().get("phase").getAsString().equals("FIGHTING")) {
                    packedKey();
                    if (!(mc.currentScreen instanceof DuelClient.LoadoutScreen)) throw new IllegalStateException("PACKED_ACTIVE_MENU_KEY");
                    ((DuelClient.LoadoutScreen) mc.currentScreen).fixtureSelect(31); NativeFixture.packedStopClicked = true; packedStep++;
                }
            } catch (Exception failure) { NativeFixture.packedClientFailure = failure.toString(); }
        }
        if (NativeFixture.duelProbe() && mc.thePlayer != null) {
            if (!mc.thePlayer.isEntityAlive()) { if (!respawnRequested) { mc.thePlayer.respawnPlayer(); respawnRequested = true; } return; }
            respawnRequested = false;
            dev.mineagent.runtime.legacy189.DuelVerification.Instruction next = dev.mineagent.runtime.legacy189.DuelVerification.instruction;
            if (next == null || next.sequence == instructionDone || DuelClient.state() == null || DuelClient.state().get("revision").getAsLong() < next.revision) return;
            try {
                boolean done = false;
                if (next.action.equals("human_chest")) done = DuelClient.fixtureChoose(0, 1, "minecraft:diamond_chestplate");
                else if (next.action.equals("ai_sword")) done = DuelClient.fixtureChoose(1, 4, "minecraft:iron_sword");
                else if ((next.action.equals("human_wool") || next.action.equals("ai_wool") || next.action.equals("start") || next.action.equals("stop")) && mc.currentScreen instanceof DuelClient.LoadoutScreen) {
                    if (next.action.equals("stop") && !DuelClient.state().get("phase").getAsString().equals("COUNTDOWN")) return;
                    if (next.action.equals("start") && !loadoutCaptured) {
                        if (!((DuelClient.LoadoutScreen) mc.currentScreen).fixtureLayoutFits()) throw new IllegalStateException("LOADOUT_CLIPPED");
                        ScreenShotHelper.saveScreenshot(mc.mcDataDir, "legacy189-loadouts.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer()); loadoutCaptured = true;
                    }
                    ((DuelClient.LoadoutScreen) mc.currentScreen).fixtureSelect(next.action.equals("human_wool") ? 20 : next.action.equals("ai_wool") ? 21 : next.action.equals("start") ? 30 : 31); done = true;
                } else if (next.action.equals("place_wool") && DuelClient.state().get("phase").getAsString().equals("COUNTDOWN")) {
                    mc.thePlayer.inventory.currentItem = 1;
                    for (int x : new int[] {-5, -4}) {
                        if (!mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), new net.minecraft.util.BlockPos(x, 100, 801), net.minecraft.util.EnumFacing.UP, new net.minecraft.util.Vec3(x + .5, 101, 801.5)))
                            throw new IllegalStateException("NATIVE_WOOL_CLICK_FAILED");
                    }
                    done = true;
                } else if (next.action.equals("break_wool")) {
                    net.minecraft.util.BlockPos at = new net.minecraft.util.BlockPos(-5, 101, 801);
                    if (mc.theWorld.isAirBlock(at)) done = true;
                    else { mc.thePlayer.inventory.currentItem = 0; mc.playerController.onPlayerDamageBlock(at, net.minecraft.util.EnumFacing.NORTH); mc.thePlayer.swingItem(); }
                }
                if (done) instructionDone = next.sequence;
            } catch (Exception failure) { dev.mineagent.runtime.legacy189.DuelVerification.clientFailure = failure.toString(); }
        }
    }
    private static void packedKey() {
        net.minecraft.client.settings.KeyBinding.onTick(ClientProxy.DUEL.getKeyCode());
        new ClientProxy().key(new net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent());
    }
    private static final class PauseProbe extends net.minecraft.client.gui.GuiIngameMenu {
        void clickDuel() throws java.io.IOException {
            for (net.minecraft.client.gui.GuiButton button : buttonList) if (button.id == DuelClient.MENU_BUTTON) {
                if (button.yPosition < 0 || button.yPosition + 20 > height) throw new IllegalStateException("PACKED_PAUSE_CLIPPED");
                mouseClicked(button.xPosition + button.getButtonWidth() / 2, button.yPosition + 10, 0);
                mouseReleased(button.xPosition + button.getButtonWidth() / 2, button.yPosition + 10, 0); return;
            }
            throw new IllegalStateException("PACKED_PAUSE_BUTTON_MISSING");
        }
    }
}
