package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.*;

/** Real client observations and native attacks; isolated test-world setup only. */
public final class NativePredictionVerification {
    public static volatile int mode=-1,clientAttacks,leftTicks,rightTicks,jumpTicks,bowPoseFrames,bowRelaxFrames,maxDrawDuration;
    public static volatile boolean poseSeen;
    private final JsonObject evidence;
    private final LegacyMotionForecast forecast=new LegacyMotionForecast();
    private final JsonArray landingSamples=new JsonArray();
    private int scene,started,nextStart,landings,predictedAt,predictedTicks,damageEvents;
    private boolean waiting,prepared,fighting,wasGround;
    private Vec3 predictedLanding,startAI;
    private double maxLandingError,maxTimingError,maxEscape;
    private float initialHealth,previousAI;
    private float forecastHealth;
    public NativePredictionVerification(EntityPlayerMP human,JsonObject evidence){this.evidence=evidence;start(human);}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private void start(EntityPlayerMP human){
        NativeDuel.Session run=NativeDuel.session(human);
        NativeDuel.choose(human,run.revision,"ai",4,scene<2?"minecraft:bow":"minecraft:diamond_sword",null);
        NativeDuel.choose(human,run.revision,"human",4,"minecraft:diamond_sword",null);NativeDuel.command(human,"ready");
        waiting=prepared=fighting=false;mode=-1;clientAttacks=leftTicks=rightTicks=jumpTicks=bowPoseFrames=bowRelaxFrames=maxDrawDuration=0;poseSeen=false;
        forecast.reset();landings=damageEvents=0;predictedLanding=null;maxLandingError=maxTimingError=maxEscape=0;
    }
    private void next(EntityPlayerMP human,int tick){mode=-1;NativeDuel.command(human,"stop");scene++;waiting=true;nextStart=tick+20;}
    public boolean tick(NativeDuel.Session run,int tick){
        EntityPlayerMP human=run.human;
        if(waiting){if(scene==4){require(evidence.get("motionForecastPassed").getAsBoolean(),"LANDING_OR_MOVEMENT_VERIFICATION_FAILED");return true;}if(tick>=nextStart)start(human);return false;}
        if(run.phase.equals("COUNTDOWN")&&!prepared){human.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(100);human.setHealth(100);prepared=true;}
        if(run.phase.equals("FIGHTING")&&!fighting){
            fighting=true;started=tick;initialHealth=human.getHealth();previousAI=run.ai.getHealth();startAI=run.ai.getPositionVector();wasGround=human.onGround;
            require(run.ai.getMaxHealth()==20&&Math.abs(run.ai.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getBaseValue()-.1)<.000001,"PREDICTION_AI_ATTRIBUTES_CHANGED");
            if(scene>=2){double height=scene==2?5:1.4;human.playerNetServerHandler.setPlayerLocation(run.ai.posX-1.2,101+height,run.ai.posZ,-90,65);human.motionX=human.motionY=human.motionZ=0;}
            mode=scene;
        }
        if(!fighting)return false;
        require(run.phase.equals("FIGHTING")&&run.ai!=null&&run.ai.isEntityAlive(),"PREDICTION_ROUND_ENDED scene="+scene+" result="+run.result);
        require(tick-started<850,"PREDICTION_TIMEOUT scene="+scene+" ranged="+run.ranged.evidence()+" pose="+bowPoseFrames+" relax="+bowRelaxFrames);
        forecast.observe(human,tick);
        if(scene==0){
            JsonObject ranged=run.ranged.evidence();
            if(ranged.get("shots").getAsInt()>=3&&human.getHealth()<initialHealth&&bowPoseFrames>=8&&bowRelaxFrames>=2&&maxDrawDuration>=10){
                evidence.add("bowAnimationAndRelease",ranged);evidence.addProperty("renderedBowPoseFrames",bowPoseFrames);evidence.addProperty("renderedReleaseFrames",bowRelaxFrames);evidence.addProperty("clientDrawDurationTicks",maxDrawDuration);next(human,tick);
            }
        }else if(scene==1){
            if(predictedLanding!=null&&human.getHealth()<forecastHealth-.001){
                JsonObject disturbed=new JsonObject();disturbed.addProperty("invalidatedByNativeDamage",true);disturbed.addProperty("at",tick);disturbed.addProperty("damage",forecastHealth-human.getHealth());landingSamples.add(disturbed);predictedLanding=null;
            }
            if(!human.onGround&&forecast.descending&&predictedLanding==null&&forecast.landing!=null&&forecast.landingTicks>=2&&forecast.landingTicks<=10){predictedLanding=forecast.landing;predictedTicks=forecast.landingTicks;predictedAt=tick;forecastHealth=human.getHealth();}
            if(!wasGround&&human.onGround&&predictedLanding!=null){
                double error=predictedLanding.distanceTo(human.getPositionVector()),timing=Math.abs(tick-predictedAt-predictedTicks);
                JsonObject sample=new JsonObject();sample.addProperty("predicted",predictedLanding.toString());sample.addProperty("actual",human.getPositionVector().toString());sample.addProperty("ticksAhead",predictedTicks);sample.addProperty("elapsed",tick-predictedAt);sample.addProperty("error",error);landingSamples.add(sample);
                maxLandingError=Math.max(maxLandingError,error);maxTimingError=Math.max(maxTimingError,timing);landings++;predictedLanding=null;
            }
            wasGround=human.onGround;
            if(tick-started>=280){
                evidence.add("landingSamples",landingSamples);evidence.addProperty("motionForecastPassed",leftTicks>=30&&rightTicks>=30&&jumpTicks>=30&&forecast.reversals>=2&&landings>=4&&maxLandingError<2&&maxTimingError<=4);
                require(run.ranged.evidence().get("shots").getAsInt()>=4&&human.getHealth()<initialHealth,"MOVING_BOW_NO_NATIVE_HIT");
                evidence.add("movingBow",run.ranged.evidence());evidence.addProperty("movingBowDamage",initialHealth-human.getHealth());evidence.addProperty("strafeReversals",forecast.reversals);evidence.addProperty("observedLandings",landings);evidence.addProperty("maxLandingPositionError",maxLandingError);evidence.addProperty("maxLandingTimingErrorTicks",maxTimingError);next(human,tick);
            }
        }else{
            evidence.add(scene==2?"highDropLatest":"closeDropLatest",run.melee.evidence());
            if(run.ai.getHealth()<previousAI-.001)damageEvents++;previousAI=run.ai.getHealth();maxEscape=Math.max(maxEscape,startAI.distanceTo(run.ai.getPositionVector()));
            if(tick-started>=240){
                JsonObject melee=run.melee.evidence();require(clientAttacks>=50&&maxEscape>2,"DROP_CLIENT_ATTACKS_OR_ESCAPE_MISSING");
                require(melee.get("escapeTicks").getAsInt()>0,"DROP_ESCAPE_NOT_USED "+melee);
                if(scene==2)require(melee.get("dropAvoidances").getAsInt()>0,"DROP_NOT_PREDICTED "+melee);
                if(scene==3)require(damageEvents>0&&melee.get("contactEscapes").getAsInt()>0,"ACTUAL_HURT_ESCAPE_NOT_OBSERVED "+melee);
                melee.addProperty("clientAttackAttempts",clientAttacks);melee.addProperty("actualDamageEvents",damageEvents);melee.addProperty("survivingAIHealth",run.ai.getHealth());melee.addProperty("maxDisplacement",maxEscape);evidence.add(scene==2?"highDropPursuit":"closeDropComboEscape",melee);next(human,tick);
            }
        }
        return false;
    }
}
