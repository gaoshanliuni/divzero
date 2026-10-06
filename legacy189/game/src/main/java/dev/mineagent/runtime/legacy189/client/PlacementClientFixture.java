package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativePlacementVerification;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;

/** Sends native UI clicks and right-click input only to the isolated Minecraft instance. */
final class PlacementClientFixture {
    static void tick(){
        Minecraft mc=Minecraft.getMinecraft();if(mc.thePlayer==null||mc.theWorld==null)return;
        NativePlacementVerification.clientSlot=mc.thePlayer.inventory.currentItem;
        ItemStack wool=mc.thePlayer.inventory.getStackInSlot(1);NativePlacementVerification.clientCount=wool==null?0:wool.stackSize;
        BlockPos anchor=NativePlacementVerification.anchor;
        if(anchor!=null)NativePlacementVerification.clientBlock=mc.theWorld.getBlockState(anchor.up()).getBlock()==Blocks.wool;
        int request=NativePlacementVerification.request;if(request==NativePlacementVerification.ack)return;
        try{
            String action=NativePlacementVerification.action;
            if(action.equals("STOP")||action.equals("START")){
                if(DuelClient.state()==null)return;
                String phase=DuelClient.state().get("phase").getAsString();if(action.equals("START")&&!phase.equals("READY")||action.equals("STOP")&&phase.equals("READY"))return;
                if(!(mc.currentScreen instanceof DuelClient.LoadoutScreen)){DuelClient.open();return;}
                ((DuelClient.LoadoutScreen)mc.currentScreen).fixtureSelect(action.equals("START")?30:31);
            }else{
                if(mc.currentScreen instanceof DuelClient.LoadoutScreen){mc.displayGuiScreen(null);return;}
                if(mc.currentScreen!=null)return;
                if(action.equals("SELECT_WOOL")){mc.thePlayer.inventory.currentItem=1;mc.playerController.updateController();}
                else if(action.equals("PLACE")){
                    if(anchor==null)return;double dx=anchor.getX()+.5-mc.thePlayer.posX,dz=anchor.getZ()+.5-mc.thePlayer.posZ,dy=anchor.getY()+1-mc.thePlayer.posY-mc.thePlayer.getEyeHeight();
                    mc.thePlayer.rotationYaw=(float)Math.toDegrees(Math.atan2(dz,dx))-90;mc.thePlayer.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
                    MovingObjectPosition hit=mc.objectMouseOver;if(hit==null||hit.typeOfHit!=MovingObjectPosition.MovingObjectType.BLOCK||!anchor.equals(hit.getBlockPos())||hit.sideHit!=EnumFacing.UP)return;
                    KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());NativePlacementVerification.clientPlacements++;
                }
            }
            NativePlacementVerification.ack=request;
        }catch(Exception error){NativePlacementVerification.clientFailure=error.toString();}
    }
}
