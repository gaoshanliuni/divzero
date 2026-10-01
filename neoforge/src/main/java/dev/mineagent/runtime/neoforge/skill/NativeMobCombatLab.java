package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native PvE learning/evaluation fixture. No mob attack goals, damage, health or cooldowns are rewritten. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class NativeMobCombatLab {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<MinecraftServer, Run> RUNS = new IdentityHashMap<>();
    private static final class Match {
        final MobArenaSchedule.Match spec;
        final IsolatedCombatArena.Bounds bounds;
        final List<Mob> enemies = new ArrayList<>();
        final Map<UUID, Vex> summons = new HashMap<>();
        final Set<UUID> fangs = new HashSet<>();
        final List<Object> frames = new ArrayList<>(), spawnChecks = new ArrayList<>();
        MineAgentPlayer actor;
        String initialModel;
        int stableTicks, identityChecks, ticks, overlapTicks;
        double damageTaken, damageDealt;
        final Map<String,Double> damageSources = new TreeMap<>();
        boolean finished;
        Match(MobArenaSchedule.Match spec) {
            this.spec = spec;
            bounds = new IsolatedCombatArena.Bounds("1vnb_"+spec.wave()+"_"+spec.lane(), (spec.lane()%3-1)*112, 1200+(spec.lane()/3)*112, 100, 24);
        }
    }
    private static final class Run {
        final ServerPlayer owner; final Object level; final boolean training;
        final List<Match> matches = new ArrayList<>(); final List<Object> results = new ArrayList<>();
        final List<CompletableFuture<?>> starts = new ArrayList<>();
        int wave; String phase="SETUP", error=""; boolean done; long started, deadline, batchDeadline;
        Run(ServerPlayer owner, boolean training) { this.owner=owner; level=owner.level(); this.training=training; }
        Map<String,Object> summary() {
            var data=new LinkedHashMap<String,Object>();
            data.put("status",done?(error.isEmpty()?"COMPLETE":"FAILED"):phase); data.put("completed",done);
            data.put("error",error); data.put("controllers","NEURAL_VS_NATIVE_MOBS"); data.put("training",training);
            data.put("wave",wave); data.put("matches",List.copyOf(results));
            data.put("active", matches.stream().filter(m->!m.finished).map(m->Map.of("lane",m.spec.lane(),"health",m.actor==null?0:m.actor.getHealth(),"enemiesAlive",m.enemies.stream().filter(Entity::isAlive).count(),"summonsAlive",m.summons.values().stream().filter(Entity::isAlive).count(),"damageTaken",m.damageTaken)).toList());
            return data;
        }
    }
    public static Map<String,Object> begin(ServerPlayer owner, boolean training) {
        if (!IsolatedCombatArena.enabled() || !System.getProperty("mineagent.skillSmokeMode", "").startsWith("mob_")) throw new SecurityException("ISOLATED_MOB_ARENA_ONLY");
        var server=owner.level().getServer(); if(!server.isSameThread()||RUNS.containsKey(server))throw new IllegalStateException("MOB_ARENA_CONTEXT");
        var run=new Run(owner,training); RUNS.put(server,run); return run.summary();
    }
    public static Map<String,Object> inspect(ServerPlayer owner) { var run=RUNS.get(owner.level().getServer()); if(run==null||run.owner!=owner)throw new SecurityException("MOB_ARENA_OWNER");return run.summary(); }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var run=RUNS.get(event.getServer()); if(run==null||run.done)return;
        try {
            if(run.owner.level()!=run.level||event.getServer().getPlayerList().getPlayer(run.owner.getUUID())!=run.owner)throw new IllegalStateException("MOB_ARENA_OWNER_CHANGED");
            if(run.phase.equals("SETUP")){prepare(run);return;}
            if(run.phase.equals("STARTING")) {
                if(run.starts.stream().anyMatch(f->!f.isDone()))return;
                for(var f:run.starts)if(!(f.join() instanceof Map<?,?> result)||!"STARTED".equals(result.get("status")))throw new IllegalStateException("MOB_ARENA_SKILL_NOT_STARTED");
                arm(run);return;
            }
            for(var m:run.matches)if(!m.finished) {
                if(MineAgentRuntimeServices.bodies(event.getServer()).body(m.actor.agentId()).orElse(null)!=m.actor)throw new IllegalStateException("MOB_ARENA_BODY_REPLACED");
                m.identityChecks++; m.ticks++;
                if(m.actor.isAlive()&&(m.actor.level()!=run.level||!m.bounds.contains(m.actor.position())))throw new IllegalStateException("MOB_ARENA_ACTOR_LEFT");
                for(var vex:run.owner.level().getEntitiesOfClass(Vex.class,m.bounds.space().inflate(24)))if(m.enemies.contains(vex.getOwner()))m.summons.put(vex.getUUID(),vex);
                var released=run.owner.level().getEntitiesOfClass(EvokerFangs.class,m.bounds.space().inflate(16));
                for(var fang:released)if(m.enemies.contains(fang.getOwner()))m.fangs.add(fang.getUUID());
                boolean alive=m.enemies.stream().anyMatch(Entity::isAlive)||m.summons.values().stream().anyMatch(Entity::isAlive);
                if(!released.isEmpty()&&m.summons.values().stream().anyMatch(Entity::isAlive))m.overlapTicks++;
                m.stableTicks=m.actor.isAlive()&&!alive&&released.isEmpty()?m.stableTicks+1:0;
                if(m.ticks%20==0 && m.frames.size()<300) {
                    var frame=new LinkedHashMap<String,Object>();frame.put("tick",m.ticks);frame.put("health",m.actor.getHealth());frame.put("position",m.actor.position().toString());frame.put("damageTaken",m.damageTaken);
                    frame.put("enemies",m.enemies.stream().map(e->Map.of("id",e.getUUID(),"health",e.getHealth(),"position",e.position().toString(),"spells",NativeCombatStates.read(e,m.actor).spells())).toList());
                    frame.put("summonsAlive",m.summons.values().stream().filter(Entity::isAlive).count()); m.frames.add(frame);
                }
                String outcome=!m.actor.isAlive()?"MOBS_WON":m.stableTicks>=80?"ACTOR_WON":System.nanoTime()>=run.deadline?"TIME_LIMIT":"";
                if(!outcome.isEmpty())finish(run,m,outcome);
            }
            if(run.matches.stream().allMatch(m->m.finished)) {run.wave++;if(run.wave>=2){run.done=true;run.phase="COMPLETE";}else{if(System.nanoTime()>=run.batchDeadline)throw new IllegalStateException("MOB_ARENA_BATCH_DEADLINE");run.phase="SETUP";}}
        } catch(Throwable failure) {run.error=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());run.done=true;run.phase="FAILED";for(var m:run.matches)if(!m.finished&&m.actor!=null)cleanup(run,m);}
    }
    private static void prepare(Run run) throws Exception {
        var owner=run.owner;var server=owner.level().getServer();owner.setGameMode(GameType.CREATIVE);
        owner.level().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,server);
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"difficulty normal");server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"time set night");
        run.matches.clear();run.starts.clear();
        for(var spec:MobArenaSchedule.wave(run.wave,Integer.getInteger("mineagent.mobCount",5),System.getProperty("mineagent.mobKind","evoker"),Long.getLong("mineagent.mobSeed",20261001)))run.matches.add(new Match(spec));
        IsolatedCombatArena.prepare(owner,run.matches.stream().map(m->m.bounds).toList());
        var view=run.matches.getFirst().bounds;owner.getAbilities().flying=true;owner.onUpdateAbilities();owner.teleportTo(owner.level(),view.x()+.5,124,view.z()-32.5,Set.of(),0,35,true);owner.setDeltaMovement(Vec3.ZERO);
        for(var m:run.matches) {
            var at=new Vec3(m.bounds.x()+.5,101,m.bounds.z()+.5);
            var definition=MineAgentRuntimeServices.bodies(server).createPersistentAt("生物训练"+run.wave+"组"+m.spec.lane(),owner.getUUID(),owner.level(),at);
            m.actor=MineAgentRuntimeServices.bodies(server).body(definition.agentId()).orElseThrow();m.spawnChecks.add(IsolatedCombatArena.place(owner,m.actor,m.bounds,0,at));
            m.actor.setGameMode(GameType.SURVIVAL);equip(m.actor);
            ActorEnhancements.update(owner,m.actor.agentId(),JSON.createObjectNode().put("actor","ai").put("expected_revision",0).put("boost",false).put("learning",run.training).put("neural",true).put("recovery",true));
            var model=LocalPolicyRuntime.snapshot(m.actor);String source=model.json();
            LocalPolicyRuntime.seedTrainingModel(m.actor,source,dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source));
            m.initialModel=LocalPolicyRuntime.trainingModel(m.actor).get("sha256").toString();
            var n=JSON.createObjectNode().put("id","mob_"+run.wave+"_"+m.spec.lane()).put("actor","ai").put("dimension",owner.level().dimension().identifier().toString()).put("expected_revision",0);
            n.putArray("min").add(m.bounds.x()-23).add(98).add(m.bounds.z()-23);n.putArray("max").add(m.bounds.x()+23).add(120).add(m.bounds.z()+23);
            n.putObject("combat").put("engagement","CLEAR_AREA").put("strategy","AUTO").put("awareness",32).put("leash",48);
            run.starts.add(SkillRuntime.get(server).start(owner,m.actor.agentId(),UUID.randomUUID(),null,n,SkillSpec.Kind.GUARD,()->!run.done));
        }
        run.phase="STARTING";
    }
    private static void arm(Run run) {
        for(var m:run.matches) {
            m.spawnChecks.add(IsolatedCombatArena.place(run.owner,m.actor,m.bounds,0,new Vec3(m.bounds.x()+.5,101,m.bounds.z()+.5)));
            if(m.actor.getHealth()!=m.actor.getMaxHealth())throw new IllegalStateException("MOB_ARENA_PRESTART_DAMAGE");
            IsolatedCombatArena.arm(run.owner,List.of(m.actor));
            for(int i=0;i<m.spec.spawns().size();i++) {
                var spawn=m.spec.spawns().get(i);String kind=m.spec.mob().equals("mixed")?List.of("evoker","zombie","skeleton").get(i%3):m.spec.mob();
                Mob mob=switch(kind){case "evoker"->EntityType.EVOKER.create(run.owner.level(),EntitySpawnReason.COMMAND);case "skeleton"->EntityType.SKELETON.create(run.owner.level(),EntitySpawnReason.COMMAND);default->EntityType.ZOMBIE.create(run.owner.level(),EntitySpawnReason.COMMAND);};
                if(mob==null)throw new IllegalStateException("MOB_ARENA_CREATE");
                mob.getRandom().setSeed(m.spec.seed()+i);mob.setPos(m.bounds.x()+.5+spawn.x(),101,m.bounds.z()+.5+spawn.z());
                mob.finalizeSpawn(run.owner.level(),run.owner.level().getCurrentDifficultyAt(mob.blockPosition()),EntitySpawnReason.COMMAND,null);
                if(!run.owner.level().noCollision(mob,mob.getBoundingBox())||run.owner.level().noCollision(mob,mob.getBoundingBox().move(0,-.08,0)))throw new IllegalStateException("MOB_ARENA_SPAWN_NOT_SUPPORTED");
                mob.setPersistenceRequired();mob.setTarget(m.actor);if(!run.owner.level().addFreshEntity(mob))throw new IllegalStateException("MOB_ARENA_ADD");m.enemies.add(mob);
                m.spawnChecks.add(Map.of("id",mob.getUUID(),"type",kind,"position",mob.position().toString(),"clear",true,"supported",true));
            }
        }
        run.started=System.nanoTime();if(run.batchDeadline==0)run.batchDeadline=run.started+java.time.Duration.ofMinutes(10).toNanos();run.deadline=Math.min(run.batchDeadline,run.started+java.time.Duration.ofMinutes(3).toNanos());run.phase="FIGHTING";
    }
    private static void equip(MineAgentPlayer b) {
        b.getInventory().clearContent();b.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));b.getInventory().setItem(1,new ItemStack(Items.COOKED_BEEF,16));
        b.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));b.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));b.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));b.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));b.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.SHIELD));b.inventoryMenu.broadcastFullState();
    }
    private static void finish(Run run,Match m,String outcome) {
        var policy=LocalPolicyRuntime.trainingModel(m.actor);
        if(!run.training&&(!m.initialModel.equals(policy.get("sha256"))||((Number)policy.get("samples")).longValue()!=0))throw new IllegalStateException("MOB_ARENA_EVALUATION_WEIGHTS_CHANGED");
        var row=new LinkedHashMap<String,Object>();row.put("scenario",m.spec);row.put("outcome",outcome);row.put("seconds",(System.nanoTime()-run.started)/1e9);row.put("ticks",m.ticks);row.put("arenaVerified",true);row.put("bodyIdentityChecks",m.identityChecks);row.put("boost",false);row.put("spawnChecks",m.spawnChecks);
        row.put("fighters",List.of(Map.of("agent",m.actor.agentId(),"role","MELEE","initialModelHash",m.initialModel,"policy",policy,"skill",SkillRuntime.get(run.owner.level().getServer()).snapshot(run.owner,m.actor.agentId()),"health",m.actor.getHealth())));
        row.put("damageTaken",m.damageTaken);row.put("damageDealt",m.damageDealt);row.put("damageSources",m.damageSources);row.put("originalMobsKilled",m.enemies.stream().filter(e->!e.isAlive()&&e.getKillCredit()==m.actor).count());row.put("originalMobsRemaining",m.enemies.stream().filter(Entity::isAlive).count());row.put("summonedMobsKilled",m.summons.values().stream().filter(e->!e.isAlive()&&e.getKillCredit()==m.actor).count());row.put("summonsRemaining",m.summons.values().stream().filter(Entity::isAlive).count());row.put("releasedFangs",m.fangs.size());row.put("overlapTicks",m.overlapTicks);row.put("frames",m.frames);
        run.results.add(row);m.finished=true;cleanup(run,m);
        // Small isolated evidence checkpoint; completed results survive a later invalid arena or crash.
        try{var path=Path.of("persistent-skill-smoke/mob-progress.json");Files.createDirectories(path.getParent());Files.writeString(path,JSON.writeValueAsString(run.summary()));}catch(Exception e){throw new IllegalStateException("MOB_ARENA_EVIDENCE_WRITE",e);}
    }
    private static void cleanup(Run run,Match m) {
        for(var mob:m.enemies)mob.discard();for(var mob:m.summons.values())mob.discard();
        for(var e:run.owner.level().getEntities((Entity)null,m.bounds.space().inflate(24),e->!(e instanceof ServerPlayer)))e.discard();
        IsolatedCombatArena.retire(run.owner,m.actor);
    }
    @net.neoforged.bus.api.SubscribeEvent public static void damage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if(!(event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level))return;var run=RUNS.get(level.getServer());if(run==null||run.done||!run.phase.equals("FIGHTING"))return;
        for(var m:run.matches)if(!m.finished){if(event.getEntity()==m.actor){m.damageTaken+=event.getHealthDamage();m.damageSources.merge(event.getSource().getMsgId(),(double)event.getHealthDamage(),Double::sum);}else if(event.getSource().getEntity()==m.actor)m.damageDealt+=event.getHealthDamage();}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){RUNS.remove(event.getServer());}
    private NativeMobCombatLab() {}
}
