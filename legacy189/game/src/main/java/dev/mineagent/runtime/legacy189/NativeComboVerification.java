package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import java.util.*;

/** Moving real-client opponents; no synthetic hit, immunity reset, or forced AI jump. */
public final class NativeComboVerification {
    public static volatile int clientMode=-1;
    public static volatile int clientMovementTicks;
    private final JsonObject evidence;
    private final JsonArray cases=new JsonArray();
    private int scenario,began,nextStart;
    private boolean prepared,started,waiting;
    private Vec3 humanStart;
    private double targetTravel;
    public NativeComboVerification(JsonObject evidence){this.evidence=evidence;}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    public boolean tick(NativeDuel.Session run,int tick){
        EntityPlayerMP human=run.human;
        if(waiting){
            if(scenario==3){evidence.add("comboScenes",cases);evidence.addProperty("source","FIXTURE_ONLY");evidence.addProperty("targetHealthForLongerMovementTest",80);return true;}
            if(tick>=nextStart){NativeDuel.command(human,"ready");waiting=false;prepared=started=false;}
            return false;
        }
        if(run.phase.equals("COUNTDOWN")&&!prepared&&run.ai.onGround){
            require(Math.abs(run.ai.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getBaseValue()-.1)<.00001,"AI_SPEED_CHANGED");
            require(run.ai.getMaxHealth()==20,"AI_HEALTH_CHANGED");
            // Only the fixture opponent has extra health so multiple native hits can be observed.
            human.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(80);human.setHealth(80);
            human.clearActivePotions();human.setAbsorptionAmount(0);clientMode=-1;clientMovementTicks=0;
            if(scenario==0){
                require(LegacyMeleeController.safeJump(run.ai,.8f,.3f),"OPEN_FLOOR_JUMP_REJECTED");
                Map<BlockPos,IBlockState> roof=new HashMap<BlockPos,IBlockState>();
                for(int x=4;x<=6;x++)for(int z=799;z<=801;z++){BlockPos p=new BlockPos(x,103,z);roof.put(p,human.worldObj.getBlockState(p));human.worldObj.setBlockState(p,Blocks.stone.getDefaultState());}
                try{require(!LegacyMeleeController.safeJump(run.ai,.8f,.3f),"LOW_CEILING_JUMP_ACCEPTED");}finally{for(Map.Entry<BlockPos,IBlockState> entry:roof.entrySet())human.worldObj.setBlockState(entry.getKey(),entry.getValue());}
                run.ai.playerNetServerHandler.setPlayerLocation(-16.5,101,800.5,90,0);
                require(!LegacyMeleeController.safeMotion(run.ai,-1.2,0,false),"EDGE_EXIT_ACCEPTED");
                run.ai.playerNetServerHandler.setPlayerLocation(5.5,101,800.5,90,0);
                evidence.addProperty("openFloorJumpAllowed",true);evidence.addProperty("ceilingAndEdgeRejected",true);
            }
            prepared=true;
        }
        if(run.phase.equals("FIGHTING")){
            if(!started){
                require(prepared,"COMBO_NOT_PREPARED");started=true;began=tick;
                // This scene starts in real close contact, then the native client keeps advancing.
                if(scenario==1){human.playerNetServerHandler.setPlayerLocation(3.3,101,800.5,-90,0);human.motionX=human.motionY=human.motionZ=0;}
                humanStart=human.getPositionVector();targetTravel=0;clientMode=scenario;
            }
            targetTravel=Math.max(targetTravel,humanStart.distanceTo(human.getPositionVector()));
            JsonObject metrics=run.melee.evidence();int hits=metrics.get("hits").getAsInt();
            if(tick-began>1100)throw new IllegalStateException("COMBO_TIMEOUT scenario="+scenario+" metrics="+metrics+" ai="+run.ai.getPositionVector()+" human="+human.getPositionVector());
            boolean ready=hits>=6&&metrics.get("maxCombo").getAsInt()>=3&&metrics.get("observedJumps").getAsInt()>0&&metrics.get("landings").getAsInt()>0;
            if(scenario==1)ready&=metrics.get("sTapReleases").getAsInt()>0&&metrics.get("observedBackwardMoves").getAsInt()>0;
            else ready&=metrics.get("wTapReleases").getAsInt()>0&&metrics.get("sprintResumes").getAsInt()>0&&metrics.get("sprintHits").getAsInt()>=2;
            if(ready){
                require(targetTravel>.5,"NATIVE_TARGET_DID_NOT_MOVE");if(scenario>0)require(clientMovementTicks>=20,"REAL_CLIENT_INPUT_MISSING");
                metrics.addProperty("opponent",new String[]{"STATIONARY","ADVANCING","STRAFING"}[scenario]);metrics.addProperty("ticks",tick-began);
                metrics.addProperty("targetDisplacement",targetTravel);metrics.addProperty("realClientMovementTicks",clientMovementTicks);cases.add(metrics);
                clientMode=-1;NativeDuel.command(human,"stop");scenario++;waiting=true;nextStart=tick+20;
            }
        }else if(started&&!waiting)throw new IllegalStateException("COMBO_ROUND_ENDED_EARLY "+run.phase+"/"+run.result);
        return false;
    }
}
