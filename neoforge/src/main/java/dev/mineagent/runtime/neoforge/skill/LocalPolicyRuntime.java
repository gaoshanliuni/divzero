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
    private static final LocalActionPolicy PRETRAINED=LocalActionPolicy.pretrained();
    private static final Map<MinecraftServer,Map<UUID,State>> ALL=new IdentityHashMap<>();
    private static final ExecutorService LEARNING=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("divzero-policy-learning").factory());
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private record Pending(ServerPlayer body,Object level,int tick,float health,Vec3 target,double distance,double[] features,long damage,LongSupplier damageNow,BooleanSupplier current){}
    private static final class State {
        LocalActionPolicy serving=PRETRAINED;final Deque<LocalActionPolicy.Sample> replay=new ArrayDeque<>();
        final UUID id;final MinecraftServer server;final Path file;
        long samples,accepted,rejected,epoch;double validationLoss;boolean training;String status="PRETRAINED";Pending pending;
        CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);
        State(ServerPlayer p,UUID id){this.id=id;server=p.level().getServer();file=server.getServerDirectory().resolve("mineagent-runtime-data/policies/"+MineAgentRuntimeServices.worldId(server)+"/"+id+".json");
            long captured=epoch;CompletableFuture.supplyAsync(()->{try{return Files.exists(file)?JSON.readTree(Files.readString(file)):null;}catch(Exception e){return null;}},IO).thenAccept(data->server.execute(()->{
                if(captured!=epoch||data==null)return;try{serving=LocalActionPolicy.parse(data.path("model").asText());samples=data.path("samples").asLong();accepted=data.path("accepted").asLong();validationLoss=data.path("validationLoss").asDouble();status="RESTORED_CHECKPOINT";}catch(Exception invalid){status="PRETRAINED_CHECKPOINT_INVALID";}
            }));
        }
        void save(){String model=serving.json();long n=samples,a=accepted;double loss=validationLoss;
            saved=saved.handle((v,e)->null).thenRunAsync(()->{try{Files.createDirectories(file.getParent());var temp=Files.createTempFile(file.getParent(),"policy-",".tmp");try{Files.writeString(temp,JSON.writeValueAsString(Map.of("model",model,"samples",n,"accepted",a,"validationLoss",loss)));Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(Exception e){throw new CompletionException(e);}},IO);
        }
    }
    private static UUID identity(ServerPlayer p){return p instanceof MineAgentPlayer body?body.agentId():p.getUUID();}
    private static State state(ServerPlayer p){return ALL.computeIfAbsent(p.level().getServer(),s->new HashMap<>()).computeIfAbsent(identity(p),id->new State(p,id));}
    public static double score(ServerPlayer p,double[] features){if(!ActorEnhancements.forBody(p).neural())return 0;return state(p).serving.cost(features);}
    public static LocalActionPolicy snapshot(ServerPlayer p){return ActorEnhancements.forBody(p).neural()?state(p).serving:null;}
    public static double[] features(ServerPlayer p,double distance,double progress,double risk,int steps,Vec3 delta,int edge,boolean opportunity,int kind,double materials){
        return new double[]{p.getHealth()/Math.max(1,p.getMaxHealth()),Math.clamp(distance/16,0,2),p.getDeltaMovement().horizontalDistance()/.4,ActorEnhancements.boost(p)?1:0,p.getAttackStrengthScale(.5f),Math.clamp(progress/8,-1,1),Math.clamp(risk/80,0,2),Math.clamp(steps/8d,0,2),Math.clamp(delta.x/8,-1,1),Math.clamp(delta.z/8,-1,1),Math.clamp(edge/8d,0,2),opportunity?1:0,kind/8d,p.onGround()?0:1,p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)/10,Math.clamp(materials/8,0,2)};
    }
    static void chose(SkillWork w,double[] features,Vec3 waypoint){
        if(!ActorEnhancements.forBody(w.player()).learning())return;var state=state(w.player());if(state.pending!=null)return;
        state.pending=new Pending(w.player(),w.player().level(),w.tick(),w.player().getHealth(),waypoint,w.player().position().distanceTo(waypoint),features.clone(),w.session.count("damageMilliHearts"),()->w.session.count("damageMilliHearts"),()->w.session.runnable()&&w.actor.current());
    }
    public static void outcome(ServerPlayer p,double[] features,double cost){
        if(!Double.isFinite(cost)||!ActorEnhancements.forBody(p).learning())return;var state=state(p);state.samples++;state.replay.addLast(new LocalActionPolicy.Sample(features,Math.clamp(cost,0,1)));while(state.replay.size()>2048)state.replay.removeFirst();
        if(state.samples%32==0)state.save();
        if(state.training||state.replay.size()<128||state.samples%32!=0)return;
        var data=new ArrayList<>(state.replay);var training=new ArrayList<LocalActionPolicy.Sample>();var validation=new ArrayList<LocalActionPolicy.Sample>();for(int i=0;i<data.size();i++)(i%5==0?validation:training).add(data.get(i));
        if(training.size()>768)training=new ArrayList<>(training.subList(training.size()-768,training.size()));
        var batch=List.copyOf(training);var holdout=List.copyOf(validation);var serving=state.serving;long epoch=state.epoch;state.training=true;state.status="TRAINING_CANDIDATE";
        CompletableFuture.supplyAsync(()->{var candidate=serving;for(int epochIndex=0;epochIndex<3;epochIndex++)candidate=candidate.train(batch,.002);return candidate;},LEARNING).whenComplete((candidate,error)->state.server.execute(()->{
            state.training=false;if(ALL.get(state.server)==null||ALL.get(state.server).get(state.id)!=state||state.epoch!=epoch||!ActorEnhancements.read(p,state.id).learning())return;
            if(error!=null){state.rejected++;state.status="TRAINING_REJECTED";return;}
            double before=serving.loss(holdout),after=candidate.loss(holdout);state.validationLoss=after;
            if(Double.isFinite(after)&&after<before&&after<=.12){state.serving=candidate;state.accepted++;state.status="LEARNED_VALIDATED";state.save();}
            else{state.rejected++;state.status="KEPT_PREVIOUS_WEIGHTS";}
        }));
    }
    public static Map<String,Object> inspect(ServerPlayer viewer,UUID id){
        var states=ALL.get(viewer.level().getServer());var state=states==null?null:states.get(id);
        return state==null?Map.of("status","PRETRAINED","version",PRETRAINED.version(),"samples",0,"source",PRETRAINED.source()):Map.of("status",state.status,"version",state.serving.version(),"samples",state.samples,"acceptedUpdates",state.accepted,"rejectedUpdates",state.rejected,"validationLoss",state.validationLoss,"source",state.serving.source(),"training",state.training);
    }
    public static void reset(ServerPlayer owner,UUID id){var states=ALL.get(owner.level().getServer());var state=states==null?null:states.get(id);if(state!=null){state.epoch++;state.serving=PRETRAINED;state.pending=null;state.replay.clear();state.status="PRETRAINED_RESTORED";state.save();}}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var states=ALL.get(event.getServer());if(states==null)return;
        for(var state:states.values()){var pending=state.pending;if(pending==null)continue;var body=pending.body;
            if(body.level()!=pending.level||body.isRemoved()&&body.isAlive()||!ActorEnhancements.forBody(body).learning()){state.pending=null;continue;}
            if(!pending.current.getAsBoolean()&&body.isAlive()){state.pending=null;continue;}
            if(event.getServer().getTickCount()-pending.tick<20&&body.isAlive())continue;state.pending=null;
            double progress=pending.distance-body.position().distanceTo(pending.target),hurt=Math.max(0,pending.health-body.getHealth())/Math.max(1,body.getMaxHealth()),damage=Math.max(0,pending.damageNow.getAsLong()-pending.damage)/1000d;
            outcome(body,pending.features,body.isAlive()?.2+hurt*2+(progress>.2?-.1:.12)-damage*.03:1);
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var states=ALL.remove(event.getServer());if(states!=null)try{CompletableFuture.allOf(states.values().stream().map(s->s.saved).toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);}catch(Exception ignored){}}
    private LocalPolicyRuntime(){}
}
