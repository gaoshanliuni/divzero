package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded real self-play, original Java-25 trainer, fresh paired evaluation, then atomic promotion. */
public final class NativeTraining {
    private static Run current;
    private static JsonObject last=new JsonObject(),lastReceipt=new JsonObject();
    private NativeTraining(){}
    public static boolean active(){return current!=null;}
    public static JsonObject status(){JsonObject value=current==null?new JsonParser().parse(last.toString()).getAsJsonObject():current.summary();value.remove("games");value.remove("replay");return value;}
    public static JsonObject receipt(){return new JsonParser().parse(lastReceipt.toString()).getAsJsonObject();}
    public static void start(EntityPlayerMP owner,int rounds){
        if(!NativeDuel.allowed(owner)||NativeDuel.session(owner).active()||active())throw new IllegalStateException("请先停止当前对局或训练");
        if(rounds<8||rounds>64||rounds%2!=0)throw new IllegalArgumentException("训练局数需为 8 到 64 的偶数");
        LegacyPolicy base=LegacyModelStore.current();if(!NativeRuntime.enabled(owner))NativeRuntime.setEnabled(owner,true);
        NativeArena.lobby(owner);current=new Run(owner,base,rounds);owner.addChatMessage(new ChatComponentText("[DivZero] 模型自对弈已开始，竞技场暂用于训练；可随时停止。"));
    }
    public static void stop(String reason){if(current!=null){Run run=current;run.cancelled.set(true);run.dispose();run.phase=reason;last=run.summary();last.addProperty("active",false);current=null;}}
    public static boolean participant(Entity entity){return current!=null&&(entity==current.left||entity==current.right);}
    public static boolean permits(Entity victim,Entity source){return current!=null&&current.phase.equals("FIGHT")&&(victim==current.left||victim==current.right)&&(source==null||victim==current.left&&source==current.right||victim==current.right&&source==current.left);}
    public static void tick(){
        Run run=current;if(run==null)return;
        MinecraftServer server=MinecraftServer.getServer();EntityPlayerMP owner=server.getConfigurationManager().getPlayerByUUID(run.owner);
        if(owner==null||!NativeDuel.allowed(owner)||!NativeRuntime.enabled(owner)||!run.session.equals(NativeRuntime.session(owner))){stop("CONTEXT_ENDED");return;}
        if(run.phase.equals("FIGHT")&&owner.posX> -17&&owner.posX<18&&owner.posZ>783&&owner.posZ<818){stop("OBSERVER_ENTERED_ARENA");return;}
        try{run.tick(owner,server.getTickCounter());}catch(Exception error){run.dispose();run.phase="FAILED";run.reason=error.getClass().getSimpleName()+": "+error.getMessage();last=run.summary();last.addProperty("active",false);current=null;LegacyMod.logger.error("Native self-play stopped",error);owner.addChatMessage(new ChatComponentText("[DivZero] 训练已停止，原模型保留："+run.reason));}
    }
    public static void decision(NativeAgent actor,EntityPlayerMP target,int tick,float beforeSelf,float beforeTarget){
        Run run=current;if(run==null||run.evaluating||!run.phase.equals("FIGHT")||!participant(actor)||run.pending.containsKey(actor.getUniqueID()))return;
        double yaw=Math.toRadians(actor.rotationYaw),vx=actor.moveStrafing*Math.cos(yaw)-actor.moveForward*Math.sin(yaw),vz=actor.moveForward*Math.cos(yaw)+actor.moveStrafing*Math.sin(yaw);
        double distance=actor.getDistanceToEntity(target),future=Math.hypot(target.posX-actor.posX-vx,target.posZ-actor.posZ-vz);
        double[] features={beforeSelf/Math.max(1,actor.getMaxHealth()),clamp(distance/16,0,2),Math.hypot(actor.motionX,actor.motionZ)/.4,0,1,
                clamp((distance-future)/8,-1,1),clamp(actor.decisionRisk/80,0,2),.125,clamp(vx/8,-1,1),clamp(vz/8,-1,1),0,ModernCombat.reachable(actor,target)?1:0,5d/8,actor.onGround?0:1,ModernCombat.baseDamage(actor)/10,0};
        run.pending.put(actor.getUniqueID(),new Pending(actor,target,tick,beforeSelf,beforeTarget,actor.getPositionVector().addVector(vx,0,vz),features));
    }
    private static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
    private static float vital(EntityPlayerMP p){return p.getHealth()+p.getAbsorptionAmount();}
    private static final class Pending {
        final NativeAgent actor;final EntityPlayerMP target;final int tick;final float self,other;final double distance;final double[] features;final Vec3 waypoint;double bestDistance;
        Pending(NativeAgent actor,EntityPlayerMP target,int tick,float self,float other,Vec3 waypoint,double[] features){this.actor=actor;this.target=target;this.tick=tick;this.self=self;this.other=other;this.waypoint=waypoint;this.distance=actor.getPositionVector().distanceTo(waypoint);bestDistance=distance;this.features=features;}
    }
    private static final class Run {
        final UUID id=UUID.randomUUID(),owner,session;final LegacyPolicy base;final int collectTarget;
        final AtomicBoolean cancelled=new AtomicBoolean();final Deque<JsonObject> replay=new ArrayDeque<JsonObject>();final JsonArray games=new JsonArray();
        final Map<UUID,Pending> pending=new HashMap<UUID,Pending>();
        final ArenaNavigator navLeft=new ArenaNavigator(),navRight=new ArenaNavigator();
        final LegacyMeleeController meleeLeft=new LegacyMeleeController(),meleeRight=new LegacyMeleeController();
        final LegacyRangedController rangedLeft=new LegacyRangedController(),rangedRight=new LegacyRangedController();
        NativeAgent left,right;LegacyPolicy candidate;String phase="CLEANING",reason="";boolean evaluating,candidateLeft;
        int cleanCursor,deadline,started,collectDone,evalDone,evalWins,evalLosses,evalDraws;double candidateLoss,baseLoss;
        CompletableFuture<JsonObject> fitting;CompletableFuture<Boolean> saving;JsonObject fit;
        Run(EntityPlayerMP owner,LegacyPolicy base,int rounds){this.owner=owner.getUniqueID();session=NativeRuntime.session(owner);this.base=base;collectTarget=rounds;}
        JsonObject summary(){JsonObject r=new JsonObject();r.addProperty("run",id.toString());r.addProperty("phase",phase);r.addProperty("active",current==this);r.addProperty("source","SELF_PLAY_NATIVE");r.addProperty("isolated",NativeFixture.requested());r.addProperty("trainingRounds",collectDone);r.addProperty("plannedTrainingRounds",collectTarget);r.addProperty("evaluationRounds",evalDone);r.addProperty("samples",replay.size());r.addProperty("baseHash",base.hash());r.addProperty("baseVersion",base.version());r.addProperty("candidateHash",candidate==null?"":candidate.hash());r.addProperty("candidateVersion",candidate==null?base.version():candidate.version());r.addProperty("wins",evalWins);r.addProperty("losses",evalLosses);r.addProperty("draws",evalDraws);r.addProperty("candidateNetHealthLost",candidateLoss);r.addProperty("baseNetHealthLost",baseLoss);r.addProperty("reason",reason);if(fit!=null)r.add("fit",new JsonParser().parse(fit.toString()));return r;}
        void tick(EntityPlayerMP ownerPlayer,int tick){
            if(phase.equals("CLEANING")){
                for(int n=0;n<4096&&cleanCursor<33*33*155;n++){int at=cleanCursor++;LegacyArenaMaterials.clear(ownerPlayer.worldObj,new BlockPos(-16+at%33,101+at/(33*33),784+at/33%33));}
                if(cleanCursor<33*33*155)return;NativeDuel.clearDrops(ownerPlayer.worldObj);
                left=NativeRuntime.createTransient(ownerPlayer,"训练模型 A");right=NativeRuntime.createTransient(ownerPlayer,"训练模型 B");
                int episode=evaluating?evalDone:collectDone;boolean bow=evaluating?episode>=4:episode%2==1;candidateLeft=episode%2==0;
                left.policy(evaluating&&candidateLeft?candidate:base);right.policy(evaluating&&!candidateLeft?candidate:base);
                String[] kit={"minecraft:iron_helmet","minecraft:iron_chestplate","minecraft:iron_leggings","minecraft:iron_boots",bow?"minecraft:bow":"minecraft:diamond_sword","minecraft:air"};
                NativeDuel.equip(left,kit,true,3);NativeDuel.equip(right,kit,true,0);
                left.playerNetServerHandler.setPlayerLocation(-4.5,101,800.5,-90,0);right.playerNetServerHandler.setPlayerLocation(5.5,101,800.5,90,0);
                navLeft.reset(left);navRight.reset(right);meleeLeft.reset();meleeRight.reset();rangedLeft.reset();rangedRight.reset();
                deadline=tick+40;phase="COUNTDOWN";return;
            }
            if(phase.equals("COUNTDOWN")){left.stopActions();right.stopActions();if(tick>=deadline){phase="FIGHT";started=tick;}return;}
            if(phase.equals("FIGHT")){
                flush(tick,false);
                if(!left.isEntityAlive()||!right.isEntityAlive()||tick-started>=600||!inside(left)||!inside(right)){finishRound(ownerPlayer,tick);return;}
                boolean leftFirst=((evaluating?evalDone:collectDone)/2)%2==0;
                if(leftFirst){drive(left,right,navLeft,meleeLeft,rangedLeft,tick);if(right.isEntityAlive())drive(right,left,navRight,meleeRight,rangedRight,tick);}
                else{drive(right,left,navRight,meleeRight,rangedRight,tick);if(left.isEntityAlive())drive(left,right,navLeft,meleeLeft,rangedLeft,tick);}return;
            }
            if(phase.equals("FITTING")&&fitting.isDone()){
                JsonObject response=fitting.join();if(!response.get("type").getAsString().equals("policy.fit"))throw new IllegalStateException("POLICY_TRAINING_FAILED");
                JsonObject value=response.getAsJsonObject("payload");fit=new JsonParser().parse(value.toString()).getAsJsonObject();fit.remove("model");
                if(value.get("baseVersion").getAsLong()!=base.version())throw new IllegalStateException("MODEL_VERSION_CHANGED");
                if(!value.get("accepted").getAsBoolean()){complete(ownerPlayer,"KEPT_PREVIOUS",value.get("reason").getAsString());return;}
                candidate=LegacyPolicy.parse(value.get("model").getAsString());if(candidate.version()<=base.version())throw new IllegalStateException("CANDIDATE_VERSION");
                evaluating=true;phase="CLEANING";cleanCursor=0;return;
            }
            if(phase.equals("SAVING")&&saving.isDone()){
                if(saving.join()){LegacyModelStore.accept(candidate);complete(ownerPlayer,"PROMOTED","LOSS_IMPROVED_AND_PAIRED_MATCHES_NON_REGRESSING");}
                else complete(ownerPlayer,"KEPT_PREVIOUS","PROMOTION_CANCELLED");
            }
        }
        void drive(NativeAgent actor,NativeAgent target,ArenaNavigator nav,LegacyMeleeController melee,LegacyRangedController ranged,int tick){
            float beforeSelf=vital(actor),beforeTarget=vital(target);LegacyCombatDriver.tick(actor,target,nav,melee,ranged,tick);decision(actor,target,tick,beforeSelf,beforeTarget);
        }
        void flush(int tick,boolean all){
            Iterator<Pending> iterator=pending.values().iterator();while(iterator.hasNext()){
                Pending p=iterator.next();p.bestDistance=Math.min(p.bestDistance,p.actor.getPositionVector().distanceTo(p.waypoint));if(!all&&tick-p.tick<12)continue;iterator.remove();
                double received=Math.max(0,p.self-vital(p.actor)),given=Math.max(0,p.other-vital(p.target));
                double progress=p.distance-p.bestDistance,maximum=Math.max(1,p.actor.getMaxHealth()),healed=Math.max(0,vital(p.actor)-p.self)/maximum;
                // Same observed-outcome scale as 26.1.2 LocalPolicyRuntime: reaching
                // the selected movement point matters, rather than always moving toward the enemy.
                double cost=clamp(!p.actor.isEntityAlive()||!inside(p.actor)?1:.25+received/maximum*1.8-healed*1.2+(progress>.2?-.1:.08)-given/maximum*.65-(!p.target.isEntityAlive()||!inside(p.target)?.35:0),0,1);
                JsonObject sample=new JsonObject();sample.add("features",new Gson().toJsonTree(p.features));sample.addProperty("cost",cost);replay.addLast(sample);
                if(replay.size()>2048)replay.removeFirst();
            }
        }
        void finishRound(EntityPlayerMP ownerPlayer,int tick){
            flush(tick,true);boolean aliveL=left.isEntityAlive()&&inside(left),aliveR=right.isEntityAlive()&&inside(right);
            String result=aliveL&&!aliveR?"LEFT_WON":aliveR&&!aliveL?"RIGHT_WON":"DRAW";
            JsonObject game=new JsonObject();game.addProperty("phase",evaluating?"HELD_OUT_NATIVE_EVALUATION":"TRAINING");game.addProperty("result",result);game.addProperty("ticks",tick-started);game.addProperty("leftHash",left.policy().hash());game.addProperty("rightHash",right.policy().hash());game.addProperty("leftHealth",vital(left));game.addProperty("rightHealth",vital(right));game.add("leftMelee",meleeLeft.evidence());game.add("rightMelee",meleeRight.evidence());game.add("leftRanged",rangedLeft.evidence());game.add("rightRanged",rangedRight.evidence());games.add(game);
            if(evaluating){evalDone++;boolean win=candidateLeft?result.equals("LEFT_WON"):result.equals("RIGHT_WON");if(result.equals("DRAW"))evalDraws++;else if(win)evalWins++;else evalLosses++;candidateLoss+=Math.max(0,20-vital(candidateLeft?left:right));baseLoss+=Math.max(0,20-vital(candidateLeft?right:left));}
            else collectDone++;
            LegacyMod.logger.info("DIVZERO_SELFPLAY round={} stage={} result={} ticks={} samples={} leftShots={} rightShots={}",collectDone+evalDone,evaluating?"EVALUATE":"COLLECT",result,tick-started,replay.size(),rangedLeft.evidence().get("shots"),rangedRight.evidence().get("shots"));
            dispose();NativeDuel.clearDrops(ownerPlayer.worldObj);
            if(evaluating&&evalDone==8){
                boolean accepted=evalWins+evalDraws*.5>=4&&evalWins+evalLosses>=4&&candidateLoss<=baseLoss*1.05+1;
                if(!accepted){complete(ownerPlayer,"KEPT_PREVIOUS","NATIVE_EVALUATION_REGRESSED");return;}
                phase="SAVING";JsonObject validation=summary();validation.add("games",games);saving=LegacyModelStore.promote(candidate,validation,cancelled);return;
            }
            if(!evaluating&&collectDone>=collectTarget&&(replay.size()>=128||collectDone>=Math.max(24,collectTarget))){
                phase="FITTING";JsonObject request=new JsonObject();request.addProperty("model",base.json());request.add("samples",new Gson().toJsonTree(replay));
                fitting=NativeService.get(ownerPlayer).thenCompose(service->service.request("policy.fit",request));return;
            }
            phase="CLEANING";cleanCursor=0;
        }
        void dispose(){pending.clear();if(left!=null){navLeft.stop(left);NativeRuntime.removeBody(left);left=null;}if(right!=null){navRight.stop(right);NativeRuntime.removeBody(right);right=null;}}
        void complete(EntityPlayerMP ownerPlayer,String phase,String reason){this.phase=phase;this.reason=reason;last=summary();last.addProperty("active",false);lastReceipt=new JsonParser().parse(last.toString()).getAsJsonObject();lastReceipt.add("games",games);lastReceipt.add("replay",new Gson().toJsonTree(replay));LegacyModelStore.receipt("selfplay-"+id,lastReceipt);current=null;ownerPlayer.addChatMessage(new ChatComponentText("[DivZero] 模型训练结束："+(phase.equals("PROMOTED")?"已验证并升级至 v"+candidate.version():"保留原模型")+"（"+reason+"）"));}
        boolean inside(Entity entity){return entity.posX> -17&&entity.posX<18&&entity.posZ>783&&entity.posZ<818&&entity.posY>=97;}
    }
}
