package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativePredictionVerification;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Test-only native input and actual RenderPlayer model observations. */
public final class PredictionClientFixture {
    static void tick(){
        Minecraft mc=Minecraft.getMinecraft();int mode=NativePredictionVerification.mode;
        if(mc.currentScreen instanceof DuelClient.LoadoutScreen)mc.displayGuiScreen(null);
        boolean active=mode>=0&&mc.thePlayer!=null&&mc.currentScreen==null&&DuelClient.state()!=null;
        Entity target=active?mc.theWorld.getEntityByID(DuelClient.state().get("aiEntity").getAsInt()):null;active&=target!=null;
        boolean left=false,right=false,jump=false,forward=false;
        if(active){
            double dx=target.posX-mc.thePlayer.posX,dz=target.posZ-mc.thePlayer.posZ,dy=target.posY+target.getEyeHeight()*.75-mc.thePlayer.posY-mc.thePlayer.getEyeHeight();
            float yaw=(float)Math.toDegrees(Math.atan2(dz,dx))-90,pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
            mc.thePlayer.rotationYaw+=Math.max(-24,Math.min(24,MathHelper.wrapAngleTo180_float(yaw-mc.thePlayer.rotationYaw)));mc.thePlayer.rotationPitch=pitch;
            if(mode==1){left=mc.thePlayer.ticksExisted/18%2==0;right=!left;jump=true;}
            if(mode>=2){forward=true;jump=mc.thePlayer.ticksExisted%24<12;if(mc.thePlayer.ticksExisted%2==0){KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());NativePredictionVerification.clientAttacks++;}}
            if(mc.thePlayer.movementInput.moveStrafe>.5)NativePredictionVerification.leftTicks++;
            if(mc.thePlayer.movementInput.moveStrafe<-.5)NativePredictionVerification.rightTicks++;
            if(!mc.thePlayer.onGround)NativePredictionVerification.jumpTicks++;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(),false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(),left);KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(),right);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(),jump);KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(),active&&mode>=2);
    }
    @SubscribeEvent public void rendered(RenderPlayerEvent.Post event){
        if(!Boolean.getBoolean("divzero.legacyPredictionFixture")||NativePredictionVerification.mode!=0||DuelClient.state()==null||event.entityPlayer.getEntityId()!=DuelClient.state().get("aiEntity").getAsInt())return;
        net.minecraft.client.model.ModelPlayer model=event.renderer.getMainModel();
        if(model.aimedBow&&event.entityPlayer.getItemInUseCount()>0&&model.bipedRightArm.rotateAngleX<-.5f&&model.bipedLeftArm.rotateAngleX<-.5f){
            NativePredictionVerification.poseSeen=true;NativePredictionVerification.bowPoseFrames++;
            NativePredictionVerification.maxDrawDuration=Math.max(NativePredictionVerification.maxDrawDuration,event.entityPlayer.getItemInUseDuration());
        }else if(NativePredictionVerification.poseSeen&&!event.entityPlayer.isUsingItem()&&!model.aimedBow)NativePredictionVerification.bowRelaxFrames++;
    }
}
