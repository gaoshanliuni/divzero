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
        final UUID id;final MinecraftServer server;final Path file;
        long samples,accepted,rejected,epoch;double validationLoss;boolean training,loading=true;String status="LOADING_CHECKPOINT";Pending pending;double bestDistance;
        CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);
        State(ServerPlayer p,UUID id){this.id=id;server=p.level().getServer();file=server.getServerDirectory().resolve("mineagent-runtime-data/policies/"+MineAgentRuntimeServices.worldId(server)+"/"+id+".json");
            long captured=epoch;CompletableFuture.supplyAsync(()->{try{return Files.exists(file)?JSON.readTree(Files.readString(file)):null;}catch(Exception e){return null;}},IO).thenAccept(data->server.execute(()->{
                if(captured!=epoch||ALL.get(server)==null||ALL.get(server).get(id)!=this)return;loading=false;if(data==null){status="PRETRAINED";return;}try{var restored=LocalActionPolicy.parse(data.path("model").asText());var records=new ArrayList<LocalActionPolicy.Sample>();if(data.path("outcomeSchema").asInt()==2&&data.has("replay")){if(!data.get("replay").isArray()||data.get("replay").size()>2048)throw new IllegalArgumentException("POLICY_REPLAY_INVALID");for(var sample:data.get("replay"))records.add(JSON.treeToValue(sample,LocalActionPolicy.Sample.class));}accepted=data.path("accepted").asLong();serving=accepted>0?restored:PRETRAINED;replay.addAll(records);samples=data.path("samples").asLong();validationLoss=data.path("validationLoss").asDouble();status=serving.source().equals(PRETRAINED.source())?"PRETRAINED_RESTORED":"RESTORED_CHECKPOINT";}catch(Exception invalid){status="PRETRAINED_CHECKPOINT_INVALID";}
            }));
        }
        void save(){if(loading)return;String model=serving.json();long n=samples,a=accepted;double loss=validationLoss;var records=List.copyOf(replay);
            saved=saved.handle((v,e)->null).thenRunAsync(()->{try{Files.createDirectories(file.getParent());var temp=Files.createTempFile(file.getParent(),"policy-",".tmp");try{Files.writeString(temp,JSON.writeValueAsString(Map.of("model",model,"samples",n,"accepted",a,"validationLoss",loss,"replay",records,"outcomeSchema",2)));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(Exception e){throw new CompletionException(e);}},IO);
        }
    }
    private static UUID identity(ServerPlayer p){return p instanceof MineAgentPlayer body?body.agentId():p.getUUID();}
    private static State state(ServerPlayer p){return state(p,identity(p));}
    private static State state(ServerPlayer context,UUID id){return ALL.computeIfAbsent(context.level().getServer(),s->new HashMap<>()).computeIfAbsent(id,key->new State(context,key));}
    public static double score(ServerPlayer p,double[] features){if(!ActorEnhancements.forBody(p).neural())return 0;return state(p).serving.cost(features);}
    public static LocalActionPolicy snapshot(ServerPlayer p){return ActorEnhancements.forBody(p).neural()?state(p).serving:null;}
    public static double[] features(ServerPlayer p,double distance,double progress,double risk,int steps,Vec3 delta,int edge,boolean opportunity,int kind,double materials){
        return new double[]{p.getHealth()/Math.max(1,p.getMaxHealth()),Math.clamp(distance/16,0,2),p.getDeltaMovement().horizontalDistance()/.4,ActorEnhancements.boost(p)?1:0,p.getAttackStrengthScale(.5f),Math.clamp(progress/8,-1,1),Math.clamp(risk/80,0,2),Math.clamp(steps/8d,0,2),Math.clamp(delta.x/8,-1,1),Math.clamp(delta.z/8,-1,1),Math.clamp(edge/8d,0,2),opportunity?1:0,kind/8d,p.onGround()?0:1,p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)/10,Math.clamp(materials/8,0,2)};
    }
    static void chose(SkillWork w,double[] features,Vec3 waypoint){
        if(!ActorEnhancements.forBody(w.player()).learning())return;var state=state(w.player());if(state.pending!=null)return;
        state.pending=new Pending(w.player(),w.player().level(),w.tick(),w.player().getHealth(),waypoint,w.player().position().distanceTo(waypoint),features.clone(),w.session.count("damageMilliHearts"),()->w.session.count("damageMilliHearts"),w.session.count("verifiedKills"),()->w.session.count("verifiedKills"),()->w.session.runnable()&&w.actor.current());state.bestDistance=state.pending.distance;
    }
    public static void outcome(ServerPlayer p,double[] features,double cost){
        if(!Double.isFinite(cost)||!ActorEnhancements.forBody(p).learning())return;var state=state(p);if(state.loading)return;state.samples++;state.replay.addLast(new LocalActionPolicy.Sample(features,Math.clamp(cost,0,1)));while(state.replay.size()>2048)state.replay.removeFirst();
        if(state.samples%32==0)state.save();
        if(state.loading||state.training||state.replay.size()<128||state.samples%32!=0)return;
        var data=new ArrayList<>(state.replay);var training=new ArrayList<LocalActionPolicy.Sample>();var validation=new ArrayList<LocalActionPolicy.Sample>();for(int i=0;i<data.size();i++)(i%5==0?validation:training).add(data.get(i));
        if(training.size()>768)training=new ArrayList<>(training.subList(training.size()-768,training.size()));
        var batch=List.copyOf(training);var holdout=List.copyOf(validation);var serving=state.serving;var anchors=state.reference;long epoch=state.epoch;state.training=true;state.status="TRAINING_CANDIDATE";
        CompletableFuture.supplyAsync(()->{var candidate=serving;for(int epochIndex=0;epochIndex<3;epochIndex++)candidate=candidate.train(batch,.002);return candidate;},LEARNING).whenComplete((candidate,error)->state.server.execute(()->{
            if(ALL.get(state.server)==null||ALL.get(state.server).get(state.id)!=state||state.epoch!=epoch)return;state.training=false;if(!ActorEnhancements.read(p,state.id).learning())return;
            if(error!=null){state.rejected++;state.status="TRAINING_REJECTED";return;}
            double before=serving.loss(holdout),after=candidate.loss(holdout);state.validationLoss=after;
            if(LocalActionPolicy.acceptsUpdate(serving,candidate,holdout,anchors)){state.serving=candidate;state.accepted++;state.status="LEARNED_VALIDATED";state.save();}
            else{state.rejected++;state.status="KEPT_PREVIOUS_WEIGHTS";}
        }));
    }
    public static Map<String,Object> inspect(ServerPlayer viewer,UUID id){
        var state=state(viewer,id);
        return state==null?Map.of("status","PRETRAINED","version",PRETRAINED.version(),"samples",0,"source",PRETRAINED.source()):Map.of("status",state.status,"version",state.serving.version(),"samples",state.samples,"acceptedUpdates",state.accepted,"rejectedUpdates",state.rejected,"validationLoss",state.validationLoss,"source",state.serving.source(),"training",state.training,"replaySize",state.replay.size(),"checkpointSaved",state.saved.isDone()&&!state.saved.isCompletedExceptionally());
    }
    public static void requireResetVersion(ServerPlayer viewer,UUID id,long version){var state=state(viewer,id);if(state.loading)throw new IllegalStateException("POLICY_CHECKPOINT_LOADING");if(state.serving.version()!=version)throw new IllegalStateException("POLICY_VERSION_CHANGED");}
    /** Test arena model pools are data only, checksum pinned and isolated from ordinary saved player models. */
    public static void seedTrainingModel(dev.mineagent.runtime.neoforge.body.MineAgentPlayer body,String source,String expectedSha256){
        if(!body.level().getServer().isSameThread()||!IsolatedCombatArena.suppressRespawn(body))throw new SecurityException("ISOLATED_TRAINING_ONLY");
        if(source.length()>131072||!modelHash(source).equals(expectedSha256))throw new IllegalArgumentException("TRAINING_MODEL_HASH");
        var policy=LocalActionPolicy.parse(source);var state=state(body);state.epoch++;state.loading=state.training=false;state.pending=null;state.serving=policy;state.reference=LocalActionPolicy.referenceSamples(policy);state.samples=state.accepted=state.rejected=0;state.replay.clear();state.status="ISOLATED_TRAINING_MODEL";state.save();
    }
    public static Map<String,Object> trainingModel(dev.mineagent.runtime.neoforge.body.MineAgentPlayer body){
        if(!body.level().getServer().isSameThread()||!IsolatedCombatArena.suppressRespawn(body))throw new SecurityException("ISOLATED_TRAINING_ONLY");var state=state(body);String model=state.serving.json();
        return Map.of("model",model,"sha256",modelHash(model),"samples",state.samples,"acceptedUpdates",state.accepted,"training",state.training,"replaySize",state.replay.size());
    }
    private static String modelHash(String source){try{return dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source);}catch(Exception error){throw new IllegalStateException("POLICY_HASH_UNAVAILABLE",error);}}
    public static void reset(ServerPlayer owner,UUID id){
        var state=state(owner,id);state.epoch++;state.loading=state.training=false;state.serving=PRETRAINED;state.pending=null;state.replay.clear();state.samples=state.accepted=state.rejected=0;state.validationLoss=0;state.status="PRETRAINED_RESTORED";state.save();
    }
    public static void preferencesChanged(ServerPlayer viewer,UUID id,ActorEnhancements.Settings before,ActorEnhancements.Settings after){
        var values=ALL.get(viewer.level().getServer());var state=values==null?null:values.get(id);if(state==null)return;
        if(before.learning()!=after.learning()||before.boost()!=after.boost()||before.neural()!=after.neural()){
            state.pending=null;if(state.loading)return;state.epoch++;state.training=false;
            state.status=state.serving.source().equals(PRETRAINED.source())&&state.serving.version()==PRETRAINED.version()?"PRETRAINED":"LEARNED_VALIDATED";
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
            if(body.level()!=pending.level||body.isRemoved()&&body.isAlive()||!ActorEnhancements.forBody(body).learning()){state.pending=null;continue;}
            if(!pending.current.getAsBoolean()&&body.isAlive()){state.pending=null;continue;}
            state.bestDistance=Math.min(state.bestDistance,body.position().distanceTo(pending.target));
            if(event.getServer().getTickCount()-pending.tick<20&&body.isAlive())continue;finishSample(state);
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var states=ALL.remove(event.getServer());if(states!=null)try{states.values().forEach(State::save);CompletableFuture.allOf(states.values().stream().map(s->s.saved).toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);}catch(Exception ignored){}}
    private LocalPolicyRuntime(){}
}
