package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Reproducible neural-vs-neural arena batches; never active outside an explicitly isolated smoke process. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class NativeSelfPlayLab {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<MinecraftServer,Run> RUNS=new IdentityHashMap<>();
    private record Model(String id,String source,String sha256){}
    private record Fighter(MineAgentPlayer body,int team,SelfPlaySchedule.Role role,Vec3 spawn,Model model){}
    private static final class Match {
        final SelfPlaySchedule.Match spec;final IsolatedCombatArena.Bounds bounds;final List<Fighter> fighters=new ArrayList<>();
        final Map<UUID,Integer> airborne=new HashMap<>();final List<Object> spawnChecks=new ArrayList<>();int defeatedAt=-1;boolean finished;
        Match(SelfPlaySchedule.Match spec){this.spec=spec;bounds=new IsolatedCombatArena.Bounds("selfplay_"+spec.wave()+"_"+spec.lane(),(spec.lane()%3-1)*48,800+(spec.lane()/3)*48,100,17);}
    }
    private static final class Run {
        final ServerPlayer owner;final Object level;final boolean training;final int waves,baseWave;final List<Model> models;
        final List<Object> results=new ArrayList<>();final List<Match> matches=new ArrayList<>();
        final List<CompletableFuture<?>> starts=new ArrayList<>();String phase="SETUP",error="";int wave;long globalDeadline,roundDeadline,roundStarted;boolean done;
        Run(ServerPlayer owner,boolean training,int waves,int baseWave,List<Model> models){this.owner=owner;level=owner.level();this.training=training;this.waves=waves;this.baseWave=baseWave;this.models=models;}
        Map<String,Object> summary(){return Map.of("status",done?(error.isEmpty()?"COMPLETE":"FAILED"):phase,"error",error,"training",training,"completed",done,"wave",wave,"waves",waves,"models",models.stream().map(m->Map.of("id",m.id,"sha256",m.sha256)).toList(),"matches",results,"controllers","NEURAL_VS_NEURAL");}
    }
    public static CompletableFuture<Map<String,Object>> begin(ServerPlayer owner,boolean training,int waves,int generation){
        if(!IsolatedCombatArena.enabled()||!System.getProperty("mineagent.skillSmokeMode","").startsWith("selfplay_")||waves<1||waves>2||generation<0)throw new SecurityException("ISOLATED_SELF_PLAY_BATCH");
        var server=owner.level().getServer();return CompletableFuture.supplyAsync(()->{
            try{byte[] raw=Files.readAllBytes(Path.of("selfplay-model-pool.json"));String expected=System.getProperty("mineagent.selfPlayPoolSha256","");
                if(raw.length>1048576||!dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(raw).equals(expected))throw new IllegalArgumentException("SELF_PLAY_POOL_HASH");
                var data=JSON.readTree(raw);var models=new ArrayList<Model>();var distinct=new HashSet<String>();
                for(var entry:data.path("models")){String source=entry.path("source").asText(),hash=entry.path("sha256").asText();if(source.length()>131072||!dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source).equals(hash))throw new IllegalArgumentException("SELF_PLAY_WEIGHT_HASH");var policy=JSON.readTree(LocalActionPolicy.parse(source).json());((com.fasterxml.jackson.databind.node.ObjectNode)policy).remove(List.of("schema","version","provenance"));models.add(new Model(entry.path("id").asText(),source,hash));distinct.add(policy.toString());}
                if(models.size()<2||models.size()>8||distinct.size()<2)throw new IllegalArgumentException("SELF_PLAY_DISTINCT_MODELS_REQUIRED");return List.copyOf(models);
            }catch(Exception failure){throw new CompletionException(failure);}
        }).thenCompose(models->server.submit(()->{if(server.getPlayerList().getPlayer(owner.getUUID())!=owner||RUNS.containsKey(server))throw new IllegalStateException("SELF_PLAY_CONTEXT_CHANGED");var run=new Run(owner,training,waves,generation*2,models);RUNS.put(server,run);return run.summary();}));
    }
    public static Map<String,Object> inspect(ServerPlayer owner){
        var run=RUNS.get(owner.level().getServer());if(run==null||run.owner!=owner)throw new SecurityException("SELF_PLAY_OWNER");
        if(run.done)return run.summary();var progress=new LinkedHashMap<String,Object>(run.summary());progress.put("active",run.matches.stream().filter(m->!m.finished).map(m->Map.of("scenario",m.spec.scenario(),"lane",m.spec.lane(),"fighters",m.fighters.stream().map(f->Map.of("id",f.body.agentId(),"team",f.team,"role",f.role,"health",f.body.getHealth(),"position",f.body.position().toString(),"model",f.model.id)).toList())).toList());return progress;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var run=RUNS.get(event.getServer());if(run==null||run.done)return;
        try{
            if(run.owner.level()!=run.level||event.getServer().getPlayerList().getPlayer(run.owner.getUUID())!=run.owner)throw new IllegalStateException("SELF_PLAY_OWNER_CONTEXT_CHANGED");
            if(run.phase.equals("SETUP")){prepare(run);return;}
            if(run.phase.equals("STARTING")){if(run.starts.stream().anyMatch(f->!f.isDone()))return;for(var future:run.starts){var result=future.join();if(!(result instanceof Map<?,?> receipt)||!Objects.equals(receipt.get("status"),"STARTED"))throw new IllegalStateException("SELF_PLAY_SKILL_NOT_STARTED");}arm(run);return;}
            if(!run.phase.equals("FIGHTING"))return;
            boolean timeout=System.nanoTime()>=run.roundDeadline;
            for(var match:run.matches)if(!match.finished){
                for(var fighter:match.fighters)if(fighter.body.isAlive()){
                    if(fighter.body.level()!=run.level||!match.bounds.contains(fighter.body.position()))throw new IllegalStateException("SELF_PLAY_BODY_LEFT_ITS_ARENA");
                    if(!fighter.body.onGround())match.airborne.merge(fighter.body.agentId(),1,Integer::sum);
                }
                boolean left=match.fighters.stream().anyMatch(f->f.team==0&&f.body.isAlive()),right=match.fighters.stream().anyMatch(f->f.team==1&&f.body.isAlive());
                if(!left||!right){if(match.defeatedAt<0)match.defeatedAt=event.getServer().getTickCount();}
                boolean settled=match.defeatedAt>=0&&event.getServer().getTickCount()-match.defeatedAt>=80;
                if(timeout||settled)finishMatch(run,match,timeout?"TIME_LIMIT":left&&!right?"LEFT_WON":right&&!left?"RIGHT_WON":"DRAW_DOUBLE_KO",settled);
            }
            if(run.matches.stream().allMatch(m->m.finished)){run.wave++;if(run.wave>=run.waves){run.done=true;run.phase="COMPLETE";}else{if(System.nanoTime()>=run.globalDeadline)throw new IllegalStateException("SELF_PLAY_BATCH_DEADLINE");run.phase="SETUP";}}
        }catch(Throwable failure){run.error=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());run.phase="FAILED";run.done=true;cleanup(run);}
    }
    private static void prepare(Run run){
        var owner=run.owner;var server=owner.level().getServer();owner.setGameMode(GameType.CREATIVE);
        owner.level().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,server);owner.level().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.PVP,true,server);
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"difficulty normal");server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"time set midnight");
        run.matches.clear();run.starts.clear();for(var spec:SelfPlaySchedule.wave(run.baseWave+run.wave,run.models.size()))run.matches.add(new Match(spec));
        IsolatedCombatArena.prepare(owner,run.matches.stream().map(m->m.bounds).toList());
        for(var match:run.matches){
            for(int team=0;team<2;team++){
                int count=team==0?match.spec.leftCount():match.spec.rightCount();var role=team==0?match.spec.left():match.spec.right();var model=run.models.get(team==0?match.spec.leftModel():match.spec.rightModel());
                for(int i=0;i<count;i++){
                    int x=match.bounds.x()+(team==0?-5:5),z=match.bounds.z()+i*2-(count-1);double y=101;
                    if(match.spec.scenario()==SelfPlaySchedule.Scenario.AIR_GROUND)x=match.bounds.x()+(team==0?-2:1);
                    if(role==SelfPlaySchedule.Role.CONFINED_START){for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=99;dy<=100;dy++)owner.level().setBlock(new BlockPos(x+dx,dy,z+dz),dx==0&&dz==0?Blocks.AIR.defaultBlockState():Blocks.DIRT.defaultBlockState(),2);y=99;}
                    if(role==SelfPlaySchedule.Role.AIRBORNE_START){for(int dy=101;dy<=102;dy++)owner.level().setBlock(new BlockPos(x,dy,z),Blocks.STONE.defaultBlockState(),2);owner.level().setBlock(new BlockPos(x+(team==0?1:-1),101,z),Blocks.STONE.defaultBlockState(),2);y=103;}
                    var at=new Vec3(x+.5,y,z+.5);var definition=MineAgentRuntimeServices.bodies(server).createPersistentAt("训练"+(match.spec.wave()+1)+"组"+(match.spec.lane()+1)+(team==0?"甲":"乙")+(i+1),owner.getUUID(),owner.level(),at);
                    var body=MineAgentRuntimeServices.bodies(server).body(definition.agentId()).orElseThrow();match.spawnChecks.add(IsolatedCombatArena.place(owner,body,match.bounds,team,at));
                    body.setGameMode(GameType.SURVIVAL);equip(body,role);var fighter=new Fighter(body,team,role,at,model);match.fighters.add(fighter);
                    var config=JSON.createObjectNode().put("actor","ai").put("expected_revision",0).put("boost",false).put("learning",run.training).put("neural",true).put("recovery",true);ActorEnhancements.update(owner,body.agentId(),config);LocalPolicyRuntime.seedTrainingModel(body,model.source,model.sha256);
                    var n=JSON.createObjectNode().put("id","selfplay_"+match.spec.wave()+"_"+match.spec.lane()+"_"+team+"_"+i).put("actor","ai").put("dimension",owner.level().dimension().identifier().toString()).put("expected_revision",0);
                    n.putArray("min").add(match.bounds.x()-16).add(97).add(match.bounds.z()-16);n.putArray("max").add(match.bounds.x()+16).add(108).add(match.bounds.z()+16);
                    n.putObject("combat").put("engagement","CLEAR_AREA").put("strategy","AUTO").put("awareness",32).put("leash",40);
                    run.starts.add(SkillRuntime.get(server).start(owner,body.agentId(),UUID.randomUUID(),null,n,SkillSpec.Kind.GUARD,()->!run.done));
                }
            }
        }
        run.phase="STARTING";
    }
    private static void arm(Run run){
        for(var match:run.matches){for(var f:match.fighters){match.spawnChecks.add(IsolatedCombatArena.place(run.owner,f.body,match.bounds,f.team,f.spawn));if(f.body.getHealth()!=f.body.getMaxHealth())throw new IllegalStateException("SELF_PLAY_PRESTART_DAMAGE");}IsolatedCombatArena.arm(run.owner,match.fighters.stream().map(Fighter::body).toList());}
        run.roundStarted=System.nanoTime();if(run.globalDeadline==0)run.globalDeadline=run.roundStarted+java.time.Duration.ofMinutes(10).toNanos();run.roundDeadline=Math.min(run.globalDeadline,run.roundStarted+java.time.Duration.ofSeconds(180).toNanos());
        for(var match:run.matches)for(var f:match.fighters)if(f.role==SelfPlaySchedule.Role.AIRBORNE_START)f.body.jumpFromGround();run.phase="FIGHTING";
    }
    private static void equip(MineAgentPlayer body,SelfPlaySchedule.Role role){
        body.getInventory().clearContent();body.getInventory().setItem(0,new ItemStack(role==SelfPlaySchedule.Role.RANGED?Items.BOW:Items.IRON_SWORD));body.getInventory().setItem(1,new ItemStack(Items.COOKED_BEEF,4));if(role==SelfPlaySchedule.Role.RANGED)body.getInventory().setItem(2,new ItemStack(Items.ARROW,128));
        body.getInventory().setItem(12,new ItemStack(Items.COBBLESTONE,16));body.getInventory().setItem(13,new ItemStack(Items.IRON_PICKAXE));body.getInventory().setItem(14,new ItemStack(Items.IRON_SHOVEL));
        body.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));body.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.IRON_CHESTPLATE));body.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.IRON_LEGGINGS));body.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));body.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);body.inventoryMenu.broadcastFullState();
    }
    private static void finishMatch(Run run,Match match,String outcome,boolean settled){
        var rows=new ArrayList<Object>();for(var f:match.fighters){var row=new LinkedHashMap<String,Object>();row.put("agent",f.body.agentId());row.put("team",f.team);row.put("role",f.role);row.put("modelId",f.model.id);row.put("initialModelHash",f.model.sha256);row.put("health",f.body.getHealth());row.put("airborneTicks",match.airborne.getOrDefault(f.body.agentId(),0));row.put("position",f.body.position().toString());row.put("policy",LocalPolicyRuntime.trainingModel(f.body));row.put("skill",SkillRuntime.get(run.owner.level().getServer()).snapshot(run.owner,f.body.agentId()));rows.add(row);}
        run.results.add(Map.of("scenario",match.spec,"outcome",outcome,"seconds",(System.nanoTime()-run.roundStarted)/1e9,"postDefeatObservationTicks",settled?80:0,"fighters",rows,"spawnChecks",match.spawnChecks,"arenaVerified",true,"boost",false));match.finished=true;
        for(var f:match.fighters)IsolatedCombatArena.retire(run.owner,f.body);
    }
    private static void cleanup(Run run){for(var match:run.matches)if(!match.finished)for(var f:match.fighters)try{IsolatedCombatArena.retire(run.owner,f.body);}catch(Exception ignored){}}
    @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){RUNS.remove(event.getServer());}
    private NativeSelfPlayLab(){}
}
