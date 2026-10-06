package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import java.util.*;

/** Isolated native secondary-equipment, rim, drop, tower, tool, pearl and sprint scenes. */
public final class NativeMobilityVerification {
    public static volatile boolean uiReady,uiPassed;
    public static volatile String clientFailure="";
    private final JsonObject evidence;
    private final Map<BlockPos,IBlockState> scene=new LinkedHashMap<BlockPos,IBlockState>();
    private NativeAgent straight,diagonal;
    private int speedAt=-1,scenario,started,nextStart,firstMove=-1,toolTicks;
    private boolean speedDone,waiting,prepared,fighting,closeBow;
    private Vec3 startAI,previousAI;
    private double maxStep;
    private float minHealth;
    public NativeMobilityVerification(JsonObject evidence){this.evidence=evidence;uiReady=true;}
    private static void require(boolean yes,String error){if(!yes)throw new IllegalStateException(error);}
    private void block(EntityPlayerMP p,int x,int y,int z,net.minecraft.block.Block b){BlockPos at=new BlockPos(x,y,z);if(!scene.containsKey(at)){require(p.worldObj.isAirBlock(at),"MOBILITY_SCENE_NOT_AIR "+at);scene.put(at,p.worldObj.getBlockState(at));}p.worldObj.setBlockState(at,b.getDefaultState(),3);}
    private void clear(EntityPlayerMP p){for(Map.Entry<BlockPos,IBlockState> e:scene.entrySet())p.worldObj.setBlockState(e.getKey(),e.getValue(),3);scene.clear();}
    private void start(EntityPlayerMP p){
        NativeDuel.Session run=NativeDuel.session(p);
        NativeDuel.choose(p,run.revision,"ai",4,"minecraft:diamond_sword",null);
        NativeDuel.choose(p,run.revision,"ai",6,scenario==4?"minecraft:bow":scenario==5?"minecraft:ender_pearl":"minecraft:shears",null);
        NativeDuel.command(p,"ready");prepared=fighting=waiting=closeBow=false;firstMove=-1;toolTicks=0;maxStep=0;
    }
    private void next(EntityPlayerMP p,int tick){clear(p);NativeDuel.command(p,"stop");scenario++;waiting=true;nextStart=tick+12;}
    public boolean tick(NativeDuel.Session run,int tick){
        EntityPlayerMP p=run.human;require(clientFailure.isEmpty(),"MOBILITY_CLIENT "+clientFailure);
        if(!uiPassed)return false;
        if(!speedDone){
            if(speedAt<0){
                if(!NativeRuntime.enabled(p))NativeRuntime.setEnabled(p,true);
                straight=NativeRuntime.createTransient(p,"原生直跑测试");diagonal=NativeRuntime.createTransient(p,"原生斜跑测试");
                straight.setPositionAndUpdate(-8.5,101,790.5);diagonal.setPositionAndUpdate(-8.5,101,794.5);speedAt=tick;
            }
            for(NativeAgent a:new NativeAgent[]{straight,diagonal}){a.rotationYaw=-90;a.rotationYawHead=-90;a.moveForward=1;a.moveStrafing=0;a.setSprinting(true);a.sprint45Requested=a==diagonal;}
            if(tick-speedAt<40)return false;
            double normal=straight.posX+8.5,fast=diagonal.posX+8.5,ratio=fast/normal;
            require(normal>8&&ratio>1.01&&ratio<1.04&&Math.abs(diagonal.posZ-794.5)<.03&&diagonal.sprint45Ticks>=30,"NATIVE_45_SPRINT_FAILED normal="+normal+" diagonal="+fast+" ratio="+ratio);
            require(Math.abs(diagonal.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getBaseValue()-.1)<.000001&&diagonal.getMaxHealth()==20,"SPRINT_ATTRIBUTES_CHANGED");
            evidence.addProperty("nativeStraightDistance",normal);evidence.addProperty("nativeDiagonalDistance",fast);evidence.addProperty("native45SpeedRatio",ratio);evidence.addProperty("native45InputTicks",diagonal.sprint45Ticks);
            NativeRuntime.removeBody(straight);NativeRuntime.removeBody(diagonal);speedDone=true;start(p);return false;
        }
        if(waiting){if(scenario==6)return true;if(tick>=nextStart)start(p);return false;}
        if(run.phase.equals("COUNTDOWN")&&!prepared){p.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(100);p.setHealth(100);prepared=true;}
        if(run.phase.equals("FIGHTING")&&!fighting){
            fighting=true;started=tick;minHealth=20;
            require(p.inventory.getStackInSlot(3)!=null&&p.inventory.getStackInSlot(3).getItem()==Items.ender_pearl&&p.inventory.getStackInSlot(3).stackSize==16,"SECONDARY_PEARL_LOADOUT");
            require(p.inventory.getStackInSlot(2)==null,"UNSELECTED_FOOD_ISSUED");
            if(scenario==0){
                require(run.ai.inventory.getStackInSlot(3).getItem()==Items.shears,"SECONDARY_SHEARS_LOADOUT");
                for(int z=790;z<=806;z++)block(p,-18,112,z,Blocks.iron_block);
                p.playerNetServerHandler.setPlayerLocation(-17.5,113,805.5,180,0);run.ai.setPositionAndUpdate(-17.5,113,790.5);run.ai.motionX=run.ai.motionY=run.ai.motionZ=0;
                run.navigation.reset(run.ai);run.navigation.move(run.ai,p,tick,false);
                require(run.navigation.directPlans>0,"EDGE_DIRECT_ROUTE_NOT_FOUND "+run.navigation.reason);
            }else if(scenario==1){
                block(p,4,104,800,Blocks.iron_block);p.playerNetServerHandler.setPlayerLocation(-4.5,101,800.5,-90,0);run.ai.setPositionAndUpdate(4.5,105,800.5);run.ai.motionX=run.ai.motionY=run.ai.motionZ=0;run.navigation.reset(run.ai);
                LegacyTraversal check=new LegacyTraversal(run.ai);require(check.acceptableDrop(4)&&!check.acceptableDrop(8),"FALL_BUDGET_BOUNDS");
            }else if(scenario==2){
                for(int y=101;y<=104;y++)for(int[] d:new int[][]{{4,800},{6,800},{5,799},{5,801}})block(p,d[0],y,d[1],Blocks.iron_block);
                block(p,7,105,800,Blocks.iron_block);p.playerNetServerHandler.setPlayerLocation(7.5,106,800.5,90,0);run.ai.setPositionAndUpdate(5.5,101,800.5);run.ai.motionX=run.ai.motionY=run.ai.motionZ=0;run.navigation.reset(run.ai);
            }else if(scenario==3){
                for(int y=101;y<=102;y++)for(int[] d:new int[][]{{-6,800},{-4,800},{-5,799},{-5,801}})block(p,d[0],y,d[1],Blocks.wool);
                for(int x=-6;x<=-4;x++)for(int z=799;z<=801;z++)block(p,x,103,z,Blocks.wool);
            }else if(scenario==5){p.playerNetServerHandler.setPlayerLocation(9.5,101,800.5,90,0);run.ai.setPositionAndUpdate(-10.5,101,800.5);run.ai.motionX=run.ai.motionY=run.ai.motionZ=0;run.navigation.reset(run.ai);}
            startAI=previousAI=run.ai.getPositionVector();
        }
        if(!fighting)return false;
        require(run.phase.equals("FIGHTING")&&run.ai!=null,"MOBILITY_ROUND_ENDED scene="+scenario+" result="+run.result);
        require(tick-started<700,"MOBILITY_TIMEOUT scene="+scenario+" nav="+run.navigation.reason+" ai="+run.ai.getPositionVector()+" direct="+run.navigation.directPlans+" placed="+run.navigation.placed());
        NativeAgent a=run.ai;minHealth=Math.min(minHealth,a.getHealth());maxStep=Math.max(maxStep,previousAI.distanceTo(a.getPositionVector()));previousAI=a.getPositionVector();
        if(firstMove<0&&startAI.distanceTo(a.getPositionVector())>.12)firstMove=tick-started;
        if(a.getHeldItem()!=null&&a.getHeldItem().getItem()==Items.shears)toolTicks++;
        if(scenario==0&&run.meleeHits>0){require(a.posY>112.8&&minHealth==20,"RIM_TRAVERSAL_FELL");evidence.addProperty("rimPursuitHit",true);evidence.addProperty("rimDirectPlans",run.navigation.directPlans);evidence.addProperty("rimStartDelayTicks",firstMove);next(p,tick);}
        else if(scenario==1&&run.meleeHits>0){require(a.onGround&&a.posY<101.1&&minHealth>=17&&minHealth<20&&firstMove<=8,"LOW_DAMAGE_DROP_FAILED health="+minHealth+" firstMove="+firstMove);evidence.addProperty("dropDamage",20-minHealth);evidence.addProperty("dropStartDelayTicks",firstMove);evidence.addProperty("dropApproachTicks",tick-started);evidence.addProperty("dropDirectPlans",run.navigation.directPlans);next(p,tick);}
        else if(scenario==2&&run.meleeHits>0){require(run.navigation.placed()>=2&&run.navigation.consumed()==run.navigation.placed()&&a.posY>102,"PVP_TOWER_NOT_EXECUTED");for(BlockPos pos:scene.keySet())require(p.worldObj.getBlockState(pos).getBlock()==Blocks.iron_block,"PERMANENT_TOWER_WALL_DESTROYED");evidence.addProperty("verticalWoolPlaced",run.navigation.placed());evidence.addProperty("verticalWoolConsumed",run.navigation.consumed());evidence.addProperty("towerApproachTicks",tick-started);next(p,tick);}
        else if(scenario==3&&run.meleeHits>0){
            // Forge 1.8.9 removes vanilla wool durability consumption from ItemShears.
            // Verify the held tool at the successful native harvest instead.
            evidence.addProperty("shearsMiningTicks",toolTicks);evidence.addProperty("shearsDurabilityUsed",a.inventory.getStackInSlot(3).getItemDamage());evidence.addProperty("shearsBlocksBroken",run.navigation.sheared());
            require(run.navigation.woolBroken>0&&toolTicks>0&&run.navigation.sheared()>0,"SHEARS_NOT_USED_NATIVELY");next(p,tick);
        }
        else if(scenario==4){
            if(!closeBow&&run.ranged.evidence().get("shots").getAsInt()>=2){require(a.inventory.currentItem==3,"SECONDARY_BOW_NOT_SELECTED");evidence.add("secondaryBow",run.ranged.evidence());p.playerNetServerHandler.setPlayerLocation(a.posX-2.2,a.posY,a.posZ,-90,0);closeBow=true;}
            if(closeBow&&run.meleeHits>0){require(a.inventory.currentItem==0&&a.loadout.switches>=2,"MELEE_SWITCH_NOT_EXECUTED");evidence.addProperty("nativeWeaponSwitches",a.loadout.switches);next(p,tick);}
        }else if(scenario==5&&maxStep>4){
            ItemStack pearls=a.inventory.getStackInSlot(3);require(a.loadout.pearlsThrown==1&&pearls!=null&&pearls.stackSize==15&&a.getHealth()<=15&&a.getHealth()>0,"NATIVE_PEARL_COST_OR_TELEPORT_MISSING");
            evidence.addProperty("pearlConsumed",1);evidence.addProperty("nativePearlTeleportDistance",maxStep);evidence.addProperty("nativePearlDamage",20-a.getHealth());next(p,tick);
        }
        return false;
    }
}
