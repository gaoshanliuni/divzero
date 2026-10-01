package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.LocalActionPolicy;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Inference never waits for disk or training. Candidate weights replace serving weights only after validation. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class LocalPolicyRuntime {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final LocalActionPolicy PRETRAINED=pretrained();
    private static LocalActionPolicy pretrained(){
        String expected=System.getProperty("mineagent.policyCandidateSha256","");
        if(!Boolean.getBoolean("mineagent.skillSmoke")||expected.isEmpty())return LocalActionPolicy.pretrained();
        if(!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("POLICY_FIXTURE_HASH");
        try{byte[] bytes=Files.readAllBytes(Path.of("policy-candidate.json"));if(bytes.length>131072||!dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes).equals(expected))throw new IllegalArgumentException("POLICY_FIXTURE_SOURCE_CHANGED");return LocalActionPolicy.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}catch(Exception failure){throw new IllegalStateException("POLICY_FIXTURE_LOAD_FAILED",failure);}
    }
    private static final List<LocalActionPolicy.Sample> REFERENCE=LocalActionPolicy.referenceSamples(PRETRAINED);
    private static final Map<MinecraftServer,Map<UUID,State>> ALL=new IdentityHashMap<>();
    private static final ExecutorService LEARNING=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("divzero-policy-learning").factory());
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private record Pending(ServerPlayer body,Object level,int tick,float health,Vec3 target,double distance,double[] features,long damage,LongSupplier damageNow,long kills,LongSupplier killsNow,BooleanSupplier current){}
    private static final class State {
        LocalActionPolicy serving=PRETRAINED;List<LocalActionPolicy.Sample> reference=REFERENCE;final Deque<LocalActionPolicy.Sample> replay=new ArrayDeque<>();
        final UUID id;final MinecraftServer server;final Path file;final ServerPlayer context;
        long samples,accepted,rejected,epoch,lastTrainedSamples=-32,inferenceCalls,lastUsedVersion;double validationLoss,lossBefore,referenceDrift,stepScale;String updateReason="NOT_TRAINED";boolean training,loading=true;String status="LOADING_CHECKPOINT";Pending pending;double bestDistance;
        boolean recording;final List<LocalActionPolicy.Sample> recorded=new ArrayList<>();
        CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);
        State(ServerPlayer p,UUID id){this.id=id;context=p;server=p.level().getServer();file=server.getServerDirectory().resolve("mineagent-runtime-data/policies/"+MineAgentRuntimeServices.worldId(server)+"/"+id+".json");
        }
        void load(){
            long captured=epoch;CompletableFuture.supplyAsync(()->{try{return Files.exists(file)?JSON.readTree(Files.readString(file)):null;}catch(Exception e){return null;}},IO).thenAccept(data->server.execute(()->{
                if(captured!=epoch||ALL.get(server)==null||ALL.get(server).get(id)!=this)return;loading=false;if(data==null){status="PRETRAINED";return;}try{var restored=LocalActionPolicy.parse(data.path("model").asText());var records=new ArrayList<LocalActionPolicy.Sample>();if(data.path("outcomeSchema").asInt()==2&&data.has("replay")){if(!data.get("replay").isArray()||data.get("replay").size()>2048)throw new IllegalArgumentException("POLICY_REPLAY_INVALID");for(var sample:data.get("replay"))records.add(JSON.treeToValue(sample,LocalActionPolicy.Sample.class));}accepted=data.path("accepted").asLong();serving=accepted>0?restored:PRETRAINED;replay.addAll(records);samples=data.path("samples").asLong();validationLoss=data.path("validationLoss").asDouble();rejected=data.path("rejected").asLong();lastTrainedSamples=data.path("learningAlgorithm").asInt()==2?data.path("lastTrainedSamples").asLong(-32):-32;updateReason=data.path("updateReason").asText("RESTORED");status=serving.source().equals(PRETRAINED.source())?"PRETRAINED_RESTORED":"RESTORED_CHECKPOINT";maybeTrain(this,context);}catch(Exception invalid){status="PRETRAINED_CHECKPOINT_INVALID";}
            }));
        }
        void save(){if(loading)return;String model=serving.json();long n=samples,a=accepted;double loss=validationLoss;var records=List.copyOf(replay);var data=new LinkedHashMap<String,Object>();data.put("model",model);data.put("samples",n);data.put("accepted",a);data.put("rejected",rejected);data.put("validationLoss",loss);data.put("replay",records);data.put("outcomeSchema",2);data.put("learningAlgorithm",2);data.put("lastTrainedSamples",lastTrainedSamples);data.put("updateReason",updateReason);data.put("referenceDrift",referenceDrift);data.put("stepScale",stepScale);
            saved=saved.handle((v,e)->null).thenRunAsync(()->{try{Files.createDirectories(file.getParent());var temp=Files.createTempFile(file.getParent(),"policy-",".tmp");try{Files.writeString(temp,JSON.writeValueAsString(data));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(Exception e){throw new CompletionException(e);}},IO);
        }
    }
    private static UUID identity(ServerPlayer p){return p instanceof MineAgentPlayer body?body.agentId():p.getUUID();}
    private static State state(ServerPlayer p){return state(p,identity(p));}
    private static State state(ServerPlayer context,UUID id){
        var states=ALL.computeIfAbsent(context.level().getServer(),s->new HashMap<>());var state=states.get(id);
        if(state==null){state=new State(context,id);states.put(id,state);state.load();}return state;
    }
    public static double score(ServerPlayer p,double[] features){if(!ActorEnhancements.forBody(p).neural())return 0;return cost(p,state(p).serving,features);}
    public static double cost(ServerPlayer p,LocalActionPolicy model,double[] features){if(model==null)return 0;var state=state(p);state.inferenceCalls++;state.lastUsedVersion=model.version();return model.cost(features);}
    public static LocalActionPolicy snapshot(ServerPlayer p){return ActorEnhancements.forBody(p).neural()?state(p).serving:null;}
    public static double[] features(ServerPlayer p,double distance,double progress,double risk,int steps,Vec3 delta,int edge,boolean opportunity,int kind,double materials){
        return new double[]{p.getHealth()/Math.max(1,p.getMaxHealth()),Math.clamp(distance/16,0,2),p.getDeltaMovement().horizontalDistance()/.4,ActorEnhancements.boost(p)?1:0,p.getAttackStrengthScale(.5f),Math.clamp(progress/8,-1,1),Math.clamp(risk/80,0,2),Math.clamp(steps/8d,0,2),Math.clamp(delta.x/8,-1,1),Math.clamp(delta.z/8,-1,1),Math.clamp(edge/8d,0,2),opportunity?1:0,kind/8d,p.onGround()?0:1,p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)/10,Math.clamp(materials/8,0,2)};
    }
    static void chose(SkillWork w,double[] features,Vec3 waypoint){
        var state=state(w.player());if(!state.recording&&!ActorEnhancements.forBody(w.player()).learning())return;if(state.pending!=null)return;
        state.pending=new Pending(w.player(),w.player().level(),w.tick(),w.player().getHealth(),waypoint,w.player().position().distanceTo(waypoint),features.clone(),w.session.count("damageMilliHearts"),()->w.session.count("damageMilliHearts"),w.session.count("verifiedKills"),()->w.session.count("verifiedKills"),()->w.session.runnable()&&w.actor.current());state.bestDistance=state.pending.distance;
    }
    public static void outcome(ServerPlayer p,double[] features,double cost){
        if(!Double.isFinite(cost))return;var state=state(p);
        if(state.recording){state.recorded.add(new LocalActionPolicy.Sample(features,Math.clamp(cost,0,1)));return;}
        if(!ActorEnhancements.forBody(p).learning()||state.loading)return;state.samples++;state.replay.addLast(new LocalActionPolicy.Sample(features,Math.clamp(cost,0,1)));while(state.replay.size()>2048)state.replay.removeFirst();
        if(state.samples%32==0)state.save();
        maybeTrain(state,p);
    }
    private static void maybeTrain(State state,ServerPlayer context){
        if(state.loading||state.training||state.recording||state.replay.size()<128||state.samples-state.lastTrainedSamples<32||!ActorEnhancements.read(context,state.id).learning())return;
        var data=List.copyOf(state.replay);var serving=state.serving;var anchors=state.reference;long epoch=state.epoch;state.training=true;state.lastTrainedSamples=state.samples;state.status="TRAINING_CANDIDATE";
        CompletableFuture.supplyAsync(()->dev.mineagent.runtime.core.task.LocalPolicyTrainer.fit(serving,data,anchors),LEARNING).whenComplete((update,error)->state.server.execute(()->{
            if(ALL.get(state.server)==null||ALL.get(state.server).get(state.id)!=state||state.epoch!=epoch)return;state.training=false;if(!ActorEnhancements.read(context,state.id).learning())return;
            if(error!=null){state.rejected++;state.status="TRAINING_REJECTED";state.updateReason="TRAINING_FAILED";state.save();return;}
            state.validationLoss=update.after();state.lossBefore=update.before();state.referenceDrift=update.referenceDrift();state.stepScale=update.scale();state.updateReason=update.reason();
            if(update.accepted()){state.serving=update.model();state.accepted++;state.status="LEARNED_VALIDATED";}
            else{state.rejected++;state.status="KEPT_PREVIOUS_WEIGHTS";}
            state.save();maybeTrain(state,context);
        }));
    }
    public static Map<String,Object> inspect(ServerPlayer viewer,UUID id){
        var state=state(viewer,id);
        var value=new LinkedHashMap<String,Object>();value.put("status",state.status);value.put("version",state.serving.version());value.put("samples",state.samples);value.put("acceptedUpdates",state.accepted);value.put("rejectedUpdates",state.rejected);value.put("validationLoss",state.validationLoss);value.put("lossBefore",state.lossBefore);value.put("referenceDrift",state.referenceDrift);value.put("stepScale",state.stepScale);value.put("updateReason",state.updateReason);value.put("source",state.serving.source());value.put("training",state.training);value.put("replaySize",state.replay.size());value.put("checkpointSaved",state.saved.isDone()&&!state.saved.isCompletedExceptionally());value.put("inferenceCalls",state.inferenceCalls);value.put("lastUsedVersion",state.lastUsedVersion);return value;
    }
    public static void requireResetVersion(ServerPlayer viewer,UUID id,long version){var state=state(viewer,id);if(state.loading)throw new IllegalStateException("POLICY_CHECKPOINT_LOADING");if(state.serving.version()!=version)throw new IllegalStateException("POLICY_VERSION_CHANGED");}
    public static void importReplayFixture(ServerPlayer viewer,UUID id,String source,String hash)throws Exception{
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!System.getProperty("mineagent.skillSmokeMode","").equals("online_replay")||!viewer.level().getServer().isSameThread())throw new SecurityException("ISOLATED_REPLAY_ONLY");
        if(source.length()>2000000||!modelHash(source).equals(hash))throw new IllegalArgumentException("REPLAY_SOURCE_HASH");var data=JSON.readTree(source);
        if(data.path("outcomeSchema").asInt()!=2||!data.path("replay").isArray()||data.path("replay").size()>2048)throw new IllegalArgumentException("REPLAY_SCHEMA");
        var model=LocalActionPolicy.parse(data.path("model").asText());var records=new ArrayList<LocalActionPolicy.Sample>();for(var sample:data.path("replay"))records.add(JSON.treeToValue(sample,LocalActionPolicy.Sample.class));
        var state=state(viewer,id);state.epoch++;state.loading=state.training=false;state.pending=null;state.serving=model;state.reference=REFERENCE;state.samples=data.path("samples").asLong();state.accepted=data.path("accepted").asLong();state.replay.clear();state.replay.addAll(records);state.lastTrainedSamples=-32;state.save();maybeTrain(state,viewer);
    }
    public static Map<String,Object> exportReplayFixture(ServerPlayer viewer,UUID id){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!System.getProperty("mineagent.skillSmokeMode","").equals("online_replay"))throw new SecurityException("ISOLATED_REPLAY_ONLY");
        var state=state(viewer,id);String source=state.serving.json();return Map.of("model",source,"sha256",modelHash(source),"state",inspect(viewer,id));
    }
    /** Test arena model pools are data only, checksum pinned and isolated from ordinary saved player models. */
    public static void seedTrainingModel(dev.mineagent.runtime.neoforge.body.MineAgentPlayer body,String source,String expectedSha256){
        if(!body.level().getServer().isSameThread()||!IsolatedCombatArena.suppressRespawn(body))throw new SecurityException("ISOLATED_TRAINING_ONLY");
        if(source.length()>131072||!modelHash(source).equals(expectedSha256))throw new IllegalArgumentException("TRAINING_MODEL_HASH");
        var policy=LocalActionPolicy.parse(source);var state=state(body);state.epoch++;state.loading=state.training=false;state.pending=null;state.serving=policy;state.reference=LocalActionPolicy.referenceSamples(policy);state.samples=state.accepted=state.rejected=0;state.lastTrainedSamples=-32;state.replay.clear();state.status="ISOLATED_TRAINING_MODEL";state.save();
    }
    public static Map<String,Object> trainingModel(dev.mineagent.runtime.neoforge.body.MineAgentPlayer body){
        if(!body.level().getServer().isSameThread()||!IsolatedCombatArena.suppressRespawn(body))throw new SecurityException("ISOLATED_TRAINING_ONLY");var state=state(body);String model=state.serving.json();
        return Map.of("model",model,"sha256",modelHash(model),"samples",state.samples,"acceptedUpdates",state.accepted,"training",state.training,"replaySize",state.replay.size(),"inferenceCalls",state.inferenceCalls,"lastUsedVersion",state.lastUsedVersion);
    }
    /** Frozen human match sampling is separate from the persistent online-learning preference. */
    static void beginRecording(MineAgentPlayer body){
        if(!NativeHumanDuel.participant(body))throw new SecurityException("HUMAN_DUEL_ONLY");
        var state=state(body);if(state.loading||state.training)throw new IllegalStateException("POLICY_NOT_READY");
        state.pending=null;state.recorded.clear();state.recording=true;
    }
    static List<LocalActionPolicy.Sample> endRecording(MineAgentPlayer body){
        var state=state(body);finishSample(state);state.recording=false;
        var samples=List.copyOf(state.recorded);state.recorded.clear();return samples;
    }
    private static String modelHash(String source){try{return dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source);}catch(Exception error){throw new IllegalStateException("POLICY_HASH_UNAVAILABLE",error);}}
    public static void reset(ServerPlayer owner,UUID id){
        var state=state(owner,id);state.epoch++;state.loading=state.training=false;state.serving=PRETRAINED;state.pending=null;state.replay.clear();state.samples=state.accepted=state.rejected=0;state.lastTrainedSamples=-32;state.validationLoss=0;state.status="PRETRAINED_RESTORED";state.save();
    }
    public static void preferencesChanged(ServerPlayer viewer,UUID id,ActorEnhancements.Settings before,ActorEnhancements.Settings after){
        var values=ALL.get(viewer.level().getServer());var state=values==null?null:values.get(id);if(state==null)return;
        if(before.learning()!=after.learning()||before.boost()!=after.boost()||before.neural()!=after.neural()){
            state.pending=null;if(state.loading)return;state.epoch++;state.training=false;
            state.status=state.serving.source().equals(PRETRAINED.source())&&state.serving.version()==PRETRAINED.version()?"PRETRAINED":"LEARNED_VALIDATED";if(!before.learning()&&after.learning()){state.lastTrainedSamples=-32;maybeTrain(state,viewer);}
        }
    }
    static void completed(SkillWork work){if(work.actor==null)return;var values=ALL.get(work.player().level().getServer());var state=values==null?null:values.get(identity(work.player()));if(state!=null&&state.pending!=null&&state.pending.body==work.player())finishSample(state);}
    private static void finishSample(State state){
        var pending=state.pending;if(pending==null)return;state.pending=null;var body=pending.body;
        state.bestDistance=Math.min(state.bestDistance,body.position().distanceTo(pending.target));
        double progress=pending.distance-state.bestDistance,maxHealth=Math.max(1,body.getMaxHealth()),hurt=Math.max(0,pending.health-body.getHealth())/maxHealth,healed=Math.max(0,body.getHealth()-pending.health)/maxHealth;
        double damage=Math.max(0,pending.damageNow.getAsLong()-pending.damage)/1000d,kills=Math.max(0,pending.killsNow.getAsLong()-pending.kills);
        outcome(body,pending.features,body.isAlive()?.25+hurt*1.8-healed*1.2+(progress>.2?-.1:.08)-damage/maxHealth*.65-kills*.35:1);
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var states=ALL.get(event.getServer());if(states==null)return;
        for(var state:states.values()){var pending=state.pending;if(pending==null)continue;var body=pending.body;
            if(body.level()!=pending.level||body.isRemoved()&&body.isAlive()||!state.recording&&!ActorEnhancements.forBody(body).learning()){state.pending=null;continue;}
            if(!pending.current.getAsBoolean()&&body.isAlive()){state.pending=null;continue;}
            state.bestDistance=Math.min(state.bestDistance,body.position().distanceTo(pending.target));
            if(event.getServer().getTickCount()-pending.tick<20&&body.isAlive())continue;finishSample(state);
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var states=ALL.remove(event.getServer());if(states!=null)try{states.values().forEach(State::save);CompletableFuture.allOf(states.values().stream().map(s->s.saved).toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);}catch(Exception ignored){}}
    private LocalPolicyRuntime(){}
}
