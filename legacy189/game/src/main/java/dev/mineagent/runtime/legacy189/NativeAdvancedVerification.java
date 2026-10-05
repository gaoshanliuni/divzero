package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.*;
import net.minecraft.util.*;
import java.util.*;

/** Isolated native cover, elevated opponent, bow release and real incoming-arrow scenes. */
public final class NativeAdvancedVerification {
    public static volatile boolean counterAttack;
    public static volatile boolean closeAdvance;
    public static volatile int closeAdvanceTicks;
    public static volatile int clientAttacks;
    private final JsonObject evidence;
    private final Map<BlockPos,IBlockState> scene=new LinkedHashMap<BlockPos,IBlockState>();
    private int scenario,started,nextStart;
    private boolean waiting,prepared,fighting;
    private float initialHealth;
    private double maxHeight,maxLateral;
    public NativeAdvancedVerification(EntityPlayerMP human,JsonObject evidence){this.evidence=evidence;start(human);}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private void start(EntityPlayerMP human){
        NativeDuel.Session run=NativeDuel.session(human);
        NativeDuel.choose(human,run.revision,"ai",4,scenario==2||scenario==3?"minecraft:bow":"minecraft:diamond_sword",null);
        NativeDuel.choose(human,run.revision,"ai",-1,"",true);NativeDuel.command(human,"ready");
        prepared=fighting=false;waiting=false;counterAttack=closeAdvance=false;clientAttacks=closeAdvanceTicks=0;
    }
    private void block(EntityPlayerMP player,int x,int y,int z,net.minecraft.block.Block block){BlockPos p=new BlockPos(x,y,z);if(!scene.containsKey(p)){require(player.worldObj.isAirBlock(p),"ADVANCED_SCENE_NOT_EMPTY "+p);scene.put(p,player.worldObj.getBlockState(p));}player.worldObj.setBlockState(p,block.getDefaultState(),3);}
    private void clear(EntityPlayerMP player){for(Map.Entry<BlockPos,IBlockState> entry:scene.entrySet())player.worldObj.setBlockState(entry.getKey(),entry.getValue(),3);scene.clear();}
    private void next(EntityPlayerMP human,int tick){counterAttack=closeAdvance=false;clear(human);NativeDuel.command(human,"stop");scenario++;waiting=true;nextStart=tick+20;}
    public boolean tick(NativeDuel.Session run,int tick){
        EntityPlayerMP human=run.human;
        if(waiting){if(scenario==6)return true;if(tick>=nextStart)start(human);return false;}
        if(run.phase.equals("COUNTDOWN")&&!prepared){human.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(100);human.setHealth(100);prepared=true;}
        if(run.phase.equals("FIGHTING")&&!fighting){
            fighting=true;started=tick;initialHealth=human.getHealth();maxHeight=run.ai.posY;maxLateral=0;
            if(scenario==0){for(int y=101;y<=102;y++)for(int[] d:new int[][]{{-6,800},{-4,800},{-5,799},{-5,801}})block(human,d[0],y,d[1],Blocks.wool);for(int x=-6;x<=-4;x++)for(int z=799;z<=801;z++)block(human,x,103,z,Blocks.wool);}
            if(scenario==1){for(int y=101;y<=105;y++)block(human,-5,y,800,Blocks.iron_block);for(int x=-6;x<=-4;x++)for(int z=799;z<=801;z++)block(human,x,105,z,Blocks.iron_block);human.playerNetServerHandler.setPlayerLocation(-4.5,106,800.5,-90,0);}
            if(scenario==3){human.playerNetServerHandler.setPlayerLocation(run.ai.posX-2.5,101,run.ai.posZ,-90,0);closeAdvance=true;}
            if(scenario==4){
                human.playerNetServerHandler.setPlayerLocation(-12.5,101,800.5,-90,0);run.ai.playerNetServerHandler.setPlayerLocation(8.5,101,800.5,90,0);run.ai.motionX=run.ai.motionY=run.ai.motionZ=0;run.navigation.reset(run.ai);run.melee.reset();
                EntityArrow arrow=new EntityArrow(human.worldObj,human,2f);Vec3 aim=run.ai.getPositionVector().addVector(0,1.6,0).subtract(arrow.getPositionVector());arrow.setThrowableHeading(aim.xCoord,aim.yCoord,aim.zCoord,2.5f,0);human.worldObj.spawnEntityInWorld(arrow);
                LegacyThreatSensor sensor=new LegacyThreatSensor();sensor.observe(run.ai,human,tick);require(sensor.projectileRisk(run.ai.getPositionVector(),6)>0,"ARROW_NOT_ON_COLLISION_COURSE");evidence.addProperty("realArrowCollisionPredicted",true);
            }
            if(scenario==5)counterAttack=true;
        }
        if(!fighting)return false;
        if(tick-started>1800)throw new IllegalStateException("ADVANCED_TIMEOUT scenario="+scenario+" phase="+run.phase+" navigation="+run.navigation.reason+" ranged="+run.ranged.evidence());
        if(scenario==5){
            if(!run.active()||tick-started>=160){
                JsonObject combat=run.melee.evidence();require(clientAttacks>=4,"COUNTER_ATTACK_INPUT_MISSING");require(combat.get("meleeThreatFrames").getAsInt()>0&&combat.get("evasiveTicks").getAsInt()>0,"MELEE_THREAT_NOT_HANDLED");
                evidence.add("counterAttackingPlayer",combat);evidence.addProperty("counterAttackAttempts",clientAttacks);evidence.addProperty("aiSurvivedCounterScene",run.ai!=null&&run.ai.isEntityAlive());next(human,tick);
            }return false;
        }
        require(run.phase.equals("FIGHTING")&&run.ai!=null,"ADVANCED_ROUND_ENDED "+run.result);
        maxHeight=Math.max(maxHeight,run.ai.posY);
        if(scenario==0&&run.meleeHits>0){require(run.navigation.woolBroken>0,"PLAYER_COVER_NOT_BROKEN");evidence.addProperty("playerCoverReached",true);evidence.addProperty("coverBlocksBroken",run.navigation.woolBroken);next(human,tick);}
        else if(scenario==1&&run.meleeHits>0){require(run.navigation.placed()>0&&run.navigation.consumed()>0&&maxHeight>102,"ELEVATED_PLAYER_NOT_BUILT_TO");for(BlockPos p:scene.keySet())require(human.worldObj.getBlockState(p).getBlock()==Blocks.iron_block,"PERMANENT_PLATFORM_DESTROYED");evidence.addProperty("elevatedPlayerReached",true);evidence.addProperty("approachBlocksPlaced",run.navigation.placed());evidence.addProperty("approachMaterialsConsumed",run.navigation.consumed());evidence.addProperty("approachHeight",maxHeight);next(human,tick);}
        else if(scenario==2){JsonObject ranged=run.ranged.evidence();if(ranged.get("shots").getAsInt()>=2&&human.getHealth()<initialHealth){require(ranged.get("arrowsConsumed").getAsInt()>=2,"BOW_AMMO_NOT_CONSUMED");evidence.add("nativeBow",ranged);evidence.addProperty("bowDamage",initialHealth-human.getHealth());next(human,tick);}}
        else if(scenario==3){JsonObject ranged=run.ranged.evidence();if(ranged.get("shots").getAsInt()>=2&&human.getHealth()<initialHealth){require(closeAdvanceTicks>=20&&ranged.get("arrowsConsumed").getAsInt()>=2,"CLOSE_BOW_PRESSURE_NOT_REAL");evidence.add("closePressureBow",ranged);evidence.addProperty("closePressureMovementTicks",closeAdvanceTicks);evidence.addProperty("closePressureBowDamage",initialHealth-human.getHealth());next(human,tick);}}
        else if(scenario==4){maxLateral=Math.max(maxLateral,Math.abs(run.ai.posZ-800.5));if(tick-started>=40){require(run.ai.projectileGuard.dodgeFrames>0&&maxLateral>.4&&run.ai.getHealth()==20,"ARROW_DODGE_FAILED lateral="+maxLateral+" health="+run.ai.getHealth());evidence.addProperty("nativeArrowDodge",true);evidence.addProperty("dodgeLateral",maxLateral);evidence.addProperty("projectileDodgeFrames",run.ai.projectileGuard.dodgeFrames);next(human,tick);}}
        return false;
    }
}
