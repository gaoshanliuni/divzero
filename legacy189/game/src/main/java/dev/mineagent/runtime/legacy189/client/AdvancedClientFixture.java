package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativeAdvancedVerification;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

final class AdvancedClientFixture {
    static void tick(){
        Minecraft mc=Minecraft.getMinecraft();if(mc.currentScreen instanceof DuelClient.LoadoutScreen)mc.displayGuiScreen(null);
        boolean attacking=(NativeAdvancedVerification.counterAttack||NativeAdvancedVerification.closeAdvance)&&mc.thePlayer!=null&&mc.currentScreen==null&&DuelClient.state()!=null;
        if(attacking){Entity target=mc.theWorld.getEntityByID(DuelClient.state().get("aiEntity").getAsInt());if(target==null)attacking=false;else{
            double dx=target.posX-mc.thePlayer.posX,dz=target.posZ-mc.thePlayer.posZ;float yaw=(float)Math.toDegrees(Math.atan2(dz,dx))-90;
            mc.thePlayer.rotationYaw+=Math.max(-12,Math.min(12,MathHelper.wrapAngleTo180_float(yaw-mc.thePlayer.rotationYaw)));mc.thePlayer.rotationPitch=0;
            if(NativeAdvancedVerification.counterAttack&&mc.thePlayer.ticksExisted%4==0){KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());NativeAdvancedVerification.clientAttacks++;}
            if(NativeAdvancedVerification.closeAdvance&&mc.thePlayer.movementInput.moveForward>.5f)NativeAdvancedVerification.closeAdvanceTicks++;
        }}
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),attacking);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(),attacking&&NativeAdvancedVerification.counterAttack);
    }
}
