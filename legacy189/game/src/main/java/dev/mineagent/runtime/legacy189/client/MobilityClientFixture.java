package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativeMobilityVerification;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

final class MobilityClientFixture {
    private static int stage;
    static void tick(){
        Minecraft mc=Minecraft.getMinecraft();if(mc.thePlayer==null||mc.theWorld==null||!NativeMobilityVerification.uiReady)return;
        for(KeyBinding key:new KeyBinding[]{mc.gameSettings.keyBindForward,mc.gameSettings.keyBindBack,mc.gameSettings.keyBindLeft,mc.gameSettings.keyBindRight,mc.gameSettings.keyBindJump,mc.gameSettings.keyBindUseItem,mc.gameSettings.keyBindAttack})KeyBinding.setKeyBindState(key.getKeyCode(),false);
        try{
            if(NativeMobilityVerification.uiPassed){if(mc.currentScreen!=null&&mc.thePlayer.isEntityAlive())mc.displayGuiScreen(null);return;}
            if(DuelClient.state()==null)return;
            if(!(mc.currentScreen instanceof DuelClient.LoadoutScreen)){DuelClient.open();return;}
            if(!((DuelClient.LoadoutScreen)mc.currentScreen).fixtureLayoutFits())throw new IllegalStateException("SECONDARY_LOADOUT_LAYOUT_CLIPPED");
            if(stage==0&&DuelClient.fixtureChoose(0,6,"minecraft:ender_pearl"))stage=1;
            else if(stage==1&&DuelClient.state().getAsJsonArray("human").get(6).getAsString().equals("minecraft:ender_pearl")&&DuelClient.fixtureChoose(1,6,"minecraft:shears"))stage=2;
            else if(stage==2&&DuelClient.state().getAsJsonArray("ai").get(6).getAsString().equals("minecraft:shears")){
                net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir,"secondary-equipment.png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());NativeMobilityVerification.uiPassed=true;mc.displayGuiScreen(null);
            }
        }catch(Exception failure){NativeMobilityVerification.clientFailure=failure.toString();}
    }
}
