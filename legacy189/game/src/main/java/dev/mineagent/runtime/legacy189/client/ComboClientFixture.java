package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativeComboVerification;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;

/** Fixture-only native player movement. Never sends desktop keyboard/mouse events. */
final class ComboClientFixture {
    static void tick(){
        Minecraft mc=Minecraft.getMinecraft();int mode=NativeComboVerification.clientMode;
        boolean active=mode>0&&mc.thePlayer!=null&&mc.currentScreen==null&&DuelClient.state()!=null&&DuelClient.state().get("phase").getAsString().equals("FIGHTING");
        boolean left=false,right=false;
        if(active){
            Entity enemy=mc.theWorld.getEntityByID(DuelClient.state().get("aiEntity").getAsInt());
            if(enemy==null)active=false;
            else{
                double dx=enemy.posX-mc.thePlayer.posX,dz=enemy.posZ-mc.thePlayer.posZ;
                if(Math.abs(mc.thePlayer.posX)>13||mc.thePlayer.posZ<787||mc.thePlayer.posZ>813){dx=.5-mc.thePlayer.posX;dz=800.5-mc.thePlayer.posZ;}
                mc.thePlayer.rotationYaw=(float)Math.toDegrees(Math.atan2(dz,dx))-90;mc.thePlayer.rotationPitch=0;
                if(mode==2){left=mc.thePlayer.ticksExisted/40%2==0;right=!left;}
                NativeComboVerification.clientMovementTicks++;
            }
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),active);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(),false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(),left);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(),right);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),active);
    }
}
