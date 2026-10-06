package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;

/** Repeats actual client placement and menu stop/restart with wool still selected. */
public final class NativePlacementVerification {
    public static volatile String action="";
    public static volatile int request,ack,clientSlot,clientCount,clientPlacements;
    public static volatile BlockPos anchor;
    public static volatile boolean clientBlock;
    public static volatile String clientFailure="";
    public static volatile String clientRay="",clientPosition="";
    public static volatile String clearedScreen="";
    private final JsonObject evidence;
    private final JsonArray attempts=new JsonArray();
    private int stage,cycle,waitUntil,began,beforeServer,beforeClient,lastAck,ackAt;
    private boolean cleanupChecked;
    public NativePlacementVerification(EntityPlayerMP player,JsonObject evidence){
        this.evidence=evidence;NativeDuel.Session run=NativeDuel.session(player);
        NativeDuel.choose(player,run.revision,"human",-1,"",true);NativeDuel.command(player,"ready");
    }
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    public static int wool(EntityPlayerMP p){ItemStack s=p.inventory.getStackInSlot(1);return s==null?0:s.stackSize;}
    private void request(String value,int tick){action=value;request++;waitUntil=tick+12;}
    public boolean tick(NativeDuel.Session run,int tick){
        if(began==0)began=tick;require(tick-began<1500,"PLACEMENT_FIXTURE_TIMEOUT stage="+stage+" phase="+run.phase+" request="+request+" ack="+ack+" ray="+clientRay+" player="+clientPosition);
        require(clientFailure.isEmpty(),"PLACEMENT_CLIENT "+clientFailure);EntityPlayerMP player=run.human;
        if(lastAck!=ack){lastAck=ack;ackAt=tick;waitUntil=Math.max(waitUntil,ackAt+4);}
        evidence.addProperty("fixtureClearedScreen",clearedScreen);
        if(stage==0&&run.phase.equals("FIGHTING")){
            player.playerNetServerHandler.setPlayerLocation(-10.5,101,810.5,90,45);anchor=new BlockPos(-13,100,810);
            require(player.worldObj.isAirBlock(anchor.up()),"OLD_ROUND_BLOCK_NOT_CLEANED");waitUntil=tick+10;stage=1;
        }else if(stage==1&&tick>=waitUntil){
            JsonObject slots=new JsonObject();slots.addProperty("round",cycle);slots.addProperty("serverSelected",player.inventory.currentItem);slots.addProperty("clientSelected",clientSlot);evidence.add("latestEquippedSlots",slots);
            request("SELECT_WOOL",tick);stage=2;
        }else if(stage==2&&ack==request&&tick>=waitUntil){
            beforeServer=wool(player);beforeClient=clientCount;request("PLACE",tick);stage=3;
        }else if(stage==3&&ack==request&&tick>=waitUntil){
            JsonObject item=observation(player);item.addProperty("round",cycle);attempts.add(item);evidence.add("placementAttempts",attempts);
            require(player.worldObj.getBlockState(anchor.up()).getBlock()==Blocks.wool&&clientBlock&&wool(player)==beforeServer-1&&clientCount==beforeClient-1&&player.inventory.currentItem==clientSlot,"RESTART_PLACEMENT_MISMATCH "+item);
            request("STOP",tick);stage=4;
        }else if(stage==4&&ack==request&&run.phase.equals("READY")&&tick>=waitUntil){
            require(player.inventory.currentItem==1&&clientSlot==1,"STOP_DID_NOT_RETAIN_SELECTED_WOOL");cycle++;
            if(cleanupChecked){evidence.addProperty("realClientPlacementAttempts",clientPlacements);evidence.addProperty("stopRestartCycles",cycle-1);action="";return true;}
            if(cycle<3){request("START",tick);stage=5;}
            else{anchor=new BlockPos(0,100,768);waitUntil=tick+10;stage=6;}
        }else if(stage==5&&ack==request&&run.active()){stage=0;}
        else if(stage==6&&tick>=waitUntil){beforeServer=wool(player);beforeClient=clientCount;request("PLACE",tick);stage=7;}
        else if(stage==7&&ack==request&&tick>=waitUntil){
            JsonObject denied=observation(player);evidence.add("protectedLobbyPlacement",denied);
            require(player.worldObj.isAirBlock(anchor.up())&&!clientBlock&&wool(player)==beforeServer&&clientCount==beforeClient,"REJECTED_PLACE_DESYNC "+denied);
            request("START",tick);stage=8;
        }else if(stage==8&&ack==request&&run.phase.equals("CLEANING")){
            player.playerNetServerHandler.setPlayerLocation(-10.5,101,810.5,90,45);anchor=new BlockPos(-13,100,810);waitUntil=tick+3;stage=9;
        }else if(stage==9&&tick>=waitUntil){beforeServer=wool(player);beforeClient=clientCount;request("PLACE",tick);stage=10;}
        else if(stage==10&&ack==request&&tick>=waitUntil){
            JsonObject denied=observation(player);evidence.add("cleaningPlacement",denied);
            require(run.phase.equals("CLEANING")&&player.worldObj.isAirBlock(anchor.up())&&!clientBlock&&wool(player)==beforeServer&&clientCount==beforeClient,"CLEANING_PLACEMENT_CONSUMED "+denied);
            request("STOP",tick);stage=11;
        }else if(stage==11&&ack==request&&run.phase.equals("READY")&&tick>=waitUntil){cleanupChecked=true;request("START",tick);stage=5;
        }
        return false;
    }
    private JsonObject observation(EntityPlayerMP player){
        JsonObject r=new JsonObject();r.addProperty("phase",NativeDuel.session(player).phase);r.addProperty("serverSlot",player.inventory.currentItem);r.addProperty("clientSlot",clientSlot);
        r.addProperty("beforeServer",beforeServer);r.addProperty("beforeClient",beforeClient);r.addProperty("afterServer",wool(player));r.addProperty("afterClient",clientCount);
        r.addProperty("serverBlock",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(player.worldObj.getBlockState(anchor.up()).getBlock())));r.addProperty("clientBlock",clientBlock);return r;
    }
}
