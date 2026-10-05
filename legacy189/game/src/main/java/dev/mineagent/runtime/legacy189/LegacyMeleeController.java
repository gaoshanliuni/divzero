package dev.mineagent.runtime.legacy189;

import com.google.gson.JsonObject;
import dev.mineagent.runtime.legacy189.navigation.ComboRhythm;
import dev.mineagent.runtime.legacy189.navigation.CombatFootwork;
import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder.Node;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;

/** Native 1.8.9 tap/pressure controller. Navigation owns obstacles; footwork owns a clear fight corridor. */
public final class LegacyMeleeController {
    private final ComboRhythm rhythm=new ComboRhythm();
    private final LegacyThreatSensor threats=new LegacyThreatSensor();
    private CombatFootwork footwork=new CombatFootwork();
    private Vec3 lastActor,lastTarget;
    private double towardX,towardZ,jumpY,maxJumpRise;
    private boolean previousBack,previousSprint,jumpActive,jumpSeen;
    private int jumpAt=-10000,backObservedAt=-10000,releaseObservedAt=-10000;
    private float jumpForward,jumpStrafe;
    private float previousVital=Float.NaN;
    private int hits,wTaps,sTaps,backMoves,sprintResumes,sprintHits,jumpRequests,jumpObserved,jumpLandings,airHits;
    private int lastHit=-10000,totalHitGap,maxHitGap;
    private String phase="APPROACH";
    private int evasiveTicks,escapeUntil=-1,lastEscape=-10000;
    private Vec3 escapeDirection;
    public int confirmedHits(){return hits;}
    public void reset(){
        rhythm.reset();footwork=new CombatFootwork();lastActor=lastTarget=null;previousBack=previousSprint=jumpActive=jumpSeen=false;
        jumpAt=backObservedAt=releaseObservedAt=lastHit=-10000;maxJumpRise=0;
        previousVital=Float.NaN;
        threats.reset();evasiveTicks=0;escapeUntil=-1;lastEscape=-10000;escapeDirection=null;
        hits=wTaps=sTaps=backMoves=sprintResumes=sprintHits=jumpRequests=jumpObserved=jumpLandings=airHits=totalHitGap=maxHitGap=0;phase="APPROACH";
    }
    public JsonObject evidence(){
        JsonObject value=new JsonObject();value.addProperty("phase",phase);value.addProperty("hits",hits);value.addProperty("maxCombo",rhythm.maximum());
        value.addProperty("wTapReleases",wTaps);value.addProperty("sTapReleases",sTaps);value.addProperty("observedBackwardMoves",backMoves);
        value.addProperty("sprintResumes",sprintResumes);value.addProperty("sprintHits",sprintHits);value.addProperty("jumpRequests",jumpRequests);
        value.addProperty("observedJumps",jumpObserved);value.addProperty("landings",jumpLandings);value.addProperty("maxJumpRise",maxJumpRise);value.addProperty("airborneHits",airHits);
        value.addProperty("meanHitGapTicks",hits<2?0:totalHitGap/(double)(hits-1));value.addProperty("maxHitGapTicks",maxHitGap);value.addProperty("evasiveTicks",evasiveTicks);value.addProperty("projectileThreatFrames",threats.projectileThreatFrames);value.addProperty("meleeThreatFrames",threats.meleeThreatFrames);return value;
    }
    public void tick(NativeDuel.Session run,int tick){
        int before=hits;tick(run.ai,run.human,run.navigation,tick);run.meleeHits+=hits-before;
    }
    public void tick(NativeAgent actor,EntityPlayerMP target,ArenaNavigator navigation,int tick){
        threats.observe(actor,target,tick);
        float vital=actor.getHealth()+actor.getAbsorptionAmount();if(!Float.isNaN(previousVital)&&vital<previousVital-.001)rhythm.interrupted();previousVital=vital;
        Vec3 at=actor.getPositionVector(),targetAt=target.getPositionVector();
        double dx=target.posX-actor.posX,dz=target.posZ-actor.posZ,distance=Math.hypot(dx,dz),nx=dx/Math.max(.001,distance),nz=dz/Math.max(.001,distance);
        double radial=lastTarget==null?0:clamp((targetAt.xCoord-lastTarget.xCoord)*nx+(targetAt.zCoord-lastTarget.zCoord)*nz,-.8,.8);
        if(previousBack&&lastActor!=null&&backObservedAt!=rhythm.lastHit()&&(at.xCoord-lastActor.xCoord)*towardX+(at.zCoord-lastActor.zCoord)*towardZ<-.015){backMoves++;backObservedAt=rhythm.lastHit();}
        if(jumpActive){
            double rise=actor.posY-jumpY;maxJumpRise=Math.max(maxJumpRise,rise);
            if(!jumpSeen&&rise>.2){jumpSeen=true;jumpObserved++;}
            if(actor.onGround&&tick>jumpAt+2){if(jumpSeen)jumpLandings++;jumpActive=jumpSeen=false;}
        }
        lastActor=at;lastTarget=targetAt;towardX=nx;towardZ=nz;previousBack=false;
        if(actor.hurtTime>0&&actor.getHealth()<actor.getMaxHealth()*.65&&distance<3.6&&tick-lastEscape>=30&&!jumpActive){
            Vec3 away=new Vec3(-nx,0,-nz);if(safeMotion(actor,away.xCoord*1.4,away.zCoord*1.4,false)){escapeDirection=away;escapeUntil=tick+8;lastEscape=tick;}
        }
        if(escapeUntil>=tick&&escapeDirection!=null&&distance<4.5&&safeMotion(actor,escapeDirection.xCoord*1.2,escapeDirection.zCoord*1.2,false)){
            actor.clearItemInUse();actor.rotationYaw=(float)Math.toDegrees(Math.atan2(escapeDirection.zCoord,escapeDirection.xCoord))-90;actor.rotationYawHead=actor.rotationYaw;
            actor.moveForward=1;actor.moveStrafing=0;actor.setSprinting(true);phase="CONTACT_ESCAPE";evasiveTicks++;return;
        }
        boolean direct=safeMotion(actor,nx*Math.min(distance,1.2),nz*Math.min(distance,1.2),false);
        boolean local=jumpActive||distance<16&&direct&&actor.canEntityBeSeen(target)&&(distance>3.5||ModernCombat.reachable(actor,target));
        if(!local){
            navigation.move(actor,target,tick,false);phase=navigation.recovering()?"TERRAIN_ESCAPE":"NAVIGATION";previousSprint=actor.isSprinting();
            if(!navigation.recovering()&&actor.moveForward>.7f&&actor.getFoodStats().getFoodLevel()>6&&!actor.isUsingItem()&&safeInput(actor,actor.moveForward,actor.moveStrafing,false))actor.setSprinting(true);
            if(!navigation.recovering())attack(actor,target,tick,distance,radial);return;
        }
        // Entering a clear corridor must not leave a stale recovery operation owning the body.
        if(navigation.recovering()){navigation.move(actor,target,tick,false);phase="TERRAIN_ESCAPE";return;}
        float forward=rhythm.forward(tick,distance,radial);
        double left=sideCost(actor,target,forward,.4f),right=sideCost(actor,target,forward,-.4f);
        int side=footwork.choose(tick,left,right);float strafe=side*.4f;
        if(side==0)strafe=0;
        if(!safeInput(actor,forward,strafe,false)){strafe=0;if(!safeInput(actor,forward,0,false))forward=0;}
        phase=rhythm.phase(tick);
        double incoming=threats.risk(actor.getPositionVector(),6);
        actor.decisionRisk=incoming;
        if(threats.activeMelee()||threats.projectileRisk(actor.getPositionVector(),6)>0){
            double[] current=worldInput(actor,forward,strafe);double best=threats.risk(at.addVector(current[0],0,current[1]),6);
            for(float[] candidate:new float[][]{{-.85f,0},{-.65f,.65f},{-.65f,-.65f},{0,1},{0,-1}}){
                if(!safeInput(actor,candidate[0],candidate[1],!actor.onGround))continue;double[] delta=worldInput(actor,candidate[0],candidate[1]);double risk=threats.risk(at.addVector(delta[0],0,delta[1]),6);
                if(risk<best-.25){best=risk;forward=candidate[0];strafe=candidate[1];phase="THREAT_DODGE";}
            }
            if(phase.equals("THREAT_DODGE"))evasiveTicks++;
        }
        if(!jumpActive&&tick-rhythm.lastHit()>0&&tick-rhythm.lastHit()<=4&&actor.getHealth()>=actor.getMaxHealth()*.4&&distance<4.2&&!actor.isUsingItem()&&!actor.isInWater()&&!actor.isOnLadder()){
            float jumpF=distance<2.25?-.6f:.8f,jumpS=side*.35f;
            double[] jumpDelta=worldInput(actor,jumpF,jumpS);
            boolean safe=safeJump(actor,jumpF,jumpS)&&threats.risk(at.addVector(jumpDelta[0],.8,jumpDelta[1]),6)<=incoming+.25;
            if(footwork.jumpReady(tick,actor.onGround,true,safe)){
                actor.setSprinting(false);
                if(actor.requestJump()){footwork.jumped(tick);jumpActive=true;jumpSeen=false;jumpAt=tick;jumpY=actor.posY;jumpForward=jumpF;jumpStrafe=jumpS;jumpRequests++;}
            }
        }
        if(jumpActive){
            boolean dodging=phase.equals("THREAT_DODGE");if(!dodging)phase="JUMP_TAP";
            if(!rhythm.resetting(tick)&&!dodging){forward=jumpForward;strafe=jumpStrafe;}
            if(!safeInput(actor,forward,strafe,true)){forward=strafe=0;}
        }
        boolean sprint=rhythm.sprint(tick,forward,jumpActive)&&actor.getFoodStats().getFoodLevel()>6&&!actor.isUsingItem();
        actor.moveForward=forward;actor.moveStrafing=strafe;actor.setSneaking(false);actor.setSprinting(sprint);
        if(sprint&&!previousSprint&&hits>0)sprintResumes++;previousSprint=sprint;
        observeRelease(tick,forward);
        previousBack=rhythm.resetting(tick)&&rhythm.backTap()&&forward<-.1;
        attack(actor,target,tick,distance,radial);
    }
    private void attack(NativeAgent actor,EntityPlayerMP target,int tick,double distance,double radial){
        if(ModernCombat.reachable(actor,target)){
            float before=target.getHealth()+target.getAbsorptionAmount();boolean sprintAtHit=actor.isSprinting(),air=!actor.onGround;
            actor.swingItem();actor.attackTargetEntityWithCurrentItem(target);
            if(target.getHealth()+target.getAbsorptionAmount()<before-.001){
                hits++;if(sprintAtHit)sprintHits++;if(air)airHits++;
                if(lastHit>-10000){int gap=tick-lastHit;totalHitGap+=gap;maxHitGap=Math.max(maxHitGap,gap);}lastHit=tick;
                rhythm.hit(tick,distance,radial);
                // A confirmed hit immediately releases sprint/forward before native travel.
                actor.setSprinting(false);previousSprint=false;
                float resetForward=rhythm.forward(tick,distance,radial);
                actor.moveForward=safeInput(actor,resetForward,actor.moveStrafing,!actor.onGround)?resetForward:0;observeRelease(tick,actor.moveForward);
                previousBack=rhythm.backTap()&&actor.moveForward<-.1;
            }
        }
    }
    private void observeRelease(int tick,float forward){
        if(!rhythm.resetting(tick)||releaseObservedAt==rhythm.lastHit())return;
        if(rhythm.backTap()&&forward<-.1){sTaps++;releaseObservedAt=rhythm.lastHit();}
        else if(!rhythm.backTap()&&Math.abs(forward)<.01){wTaps++;releaseObservedAt=rhythm.lastHit();}
    }
    private double sideCost(NativeAgent actor,EntityPlayerMP target,float forward,float strafe){
        double[] delta=worldInput(actor,forward,strafe);if(!safeMotion(actor,delta[0]*.8,delta[1]*.8,false))return Double.POSITIVE_INFINITY;
        double distance=actor.getDistanceToEntity(target),next=Math.hypot(target.posX-actor.posX-delta[0],target.posZ-actor.posZ-delta[1]);
        double risk=Math.max(0,2.1-next)*12+threats.risk(actor.getPositionVector().addVector(delta[0],0,delta[1]),6)*4;
        double[] features={actor.getHealth()/Math.max(1,actor.getMaxHealth()),clamp(distance/16,0,2),Math.hypot(actor.motionX,actor.motionZ)/.4,0,1,
                clamp((distance-next)/8,-1,1),clamp(risk/80,0,2),.125,delta[0]/8,delta[1]/8,0,1,5d/8,actor.onGround?0:1,ModernCombat.baseDamage(actor)/10,0};
        return risk+actor.policy().cost(features)*5;
    }
    private static double[] worldInput(NativeAgent actor,float forward,float strafe){double yaw=Math.toRadians(actor.rotationYaw),length=Math.max(1,Math.hypot(forward,strafe));return new double[]{(strafe*Math.cos(yaw)-forward*Math.sin(yaw))/length,(forward*Math.cos(yaw)+strafe*Math.sin(yaw))/length};}
    private static boolean safeInput(NativeAgent actor,float forward,float strafe,boolean airborne){double[] delta=worldInput(actor,forward,strafe);return safeMotion(actor,delta[0]*.8,delta[1]*.8,airborne);}
    public static boolean safeMotion(NativeAgent actor,double dx,double dz,boolean airborne){
        LegacyTraversal check=new LegacyTraversal(actor);Node from=check.closest(actor.getPositionVector());if(from==null)return false;
        for(int i=0;i<=6;i++){
            double x=actor.posX+dx*i/6,z=actor.posZ+dz*i/6;Node floor=check.closest(new Vec3(x,from.y(),z));
            if(floor==null||Math.abs(floor.y()-from.y())>.1||!check.clear(new Vec3(x,airborne?actor.posY:Math.max(actor.posY,floor.y()),z),false))return false;
        }return true;
    }
    public static boolean safeJump(NativeAgent actor,float forward,float strafe){
        if(!actor.onGround||actor.isInWater()||actor.isOnLadder())return false;
        LegacyTraversal check=new LegacyTraversal(actor);Node start=check.closest(actor.getPositionVector());if(start==null)return false;
        double[] input=worldInput(actor,forward,strafe);double x=actor.posX,y=actor.posY,z=actor.posZ,vx=actor.motionX,vz=actor.motionZ,vy=.42;
        if(actor.isPotionActive(Potion.jump))vy+=(actor.getActivePotionEffect(Potion.jump).getAmplifier()+1)*.1;
        for(int tick=1;tick<=30;tick++){
            vx+=input[0]*.02;vz+=input[1]*.02;x+=vx;z+=vz;y+=vy;vy=(vy-.08)*.98;vx*=.91;vz*=.91;
            if(!check.clear(new Vec3(x,Math.max(start.y(),y),z),false))return false;
            if(y<=start.y()){
                Node landing=check.closest(new Vec3(x,start.y(),z));
                return landing!=null&&Math.abs(landing.y()-start.y())<.25&&check.neighbors(landing).size()>=2;
            }
        }return false;
    }
    private static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
}
