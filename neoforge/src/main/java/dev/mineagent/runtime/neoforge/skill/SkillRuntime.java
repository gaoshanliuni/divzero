package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.agent.BodyDomain;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.task.SkillSession.State;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.neoforge.task.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Persistent intent scheduler. Model latency is outside every local skill work cycle. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class SkillRuntime {
    private static final ObjectMapper JSON=new ObjectMapper();private static final String SPACE="player_skills_v1";
    private static final Map<MinecraftServer,SkillRuntime> ALL=new IdentityHashMap<>();private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    final MinecraftServer server;final WorkReservations<String> reservations=new WorkReservations<>();private final Map<UUID,SkillWork> work=new LinkedHashMap<>();private final CompletableFuture<Void> loaded=new CompletableFuture<>();private int scanBudget;private long deadline;
    private final LinkedHashMap<UUID,SkillWork> history=new LinkedHashMap<>();
    private final Map<UUID,LinkedHashSet<SkillWork>> byEntity=new HashMap<>();private final Map<UUID,UUID> indexedActor=new HashMap<>();
    private final Map<UUID,LinkedHashSet<SkillWork>> byOwner=new HashMap<>();
    private final Map<UUID,SkillWork> byOperation=new HashMap<>();private final Map<UUID,Set<UUID>> indexedOperations=new HashMap<>();
    private void cacheHistory(SkillWork w){history.put(w.token(),w);while(history.size()>128)history.remove(history.keySet().iterator().next());}
    void index(SkillWork w){
        UUID prior=indexedActor.remove(w.token());if(prior!=null){var values=byEntity.get(prior);if(values!=null){values.remove(w);if(values.isEmpty())byEntity.remove(prior);}}
        for(UUID operation:indexedOperations.getOrDefault(w.token(),Set.of()))byOperation.remove(operation,w);indexedOperations.remove(w.token());
        if(w.session.terminal()){var owned=byOwner.get(w.session.owner());if(owned!=null){owned.remove(w);if(owned.isEmpty())byOwner.remove(w.session.owner());}work.remove(w.token(),w);cacheHistory(w);return;}
        byOwner.computeIfAbsent(w.session.owner(),id->new LinkedHashSet<>()).add(w);
        work.put(w.token(),w);
        if(w.actor!=null){UUID id=w.actor.player().getUUID();byEntity.computeIfAbsent(id,k->new LinkedHashSet<>()).add(w);indexedActor.put(w.token(),id);}
        var operations=new HashSet<UUID>();operations.add(w.token());if(w.operation!=null)operations.add(w.operation);if(w.combatOperation!=null)operations.add(w.combatOperation);
        for(UUID id:operations)byOperation.put(id,w);indexedOperations.put(w.token(),Set.copyOf(operations));
    }
    private List<SkillWork> forEntity(ServerPlayer player){return List.copyOf(byEntity.getOrDefault(player.getUUID(),new LinkedHashSet<>()));}
    private CompletableFuture<SkillWork> known(UUID operation){var value=work.get(operation);if(value==null)value=history.get(operation);if(value!=null)return CompletableFuture.completedFuture(value);UUID world=MineAgentRuntimeServices.worldId(server);
        return CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(database())){var row=db.get(world,SPACE,operation.toString());return row.isEmpty()?null:new SkillWork(this,SkillSession.restore(JSON.readValue(row.orElseThrow().payload(),SkillSession.Snapshot.class)),row.orElseThrow().revision());}catch(Exception error){throw new CompletionException(error);}},IO);
    }
    private final Set<UUID> legacyPending=new HashSet<>();
    private SkillRuntime(MinecraftServer server){this.server=server;CropAdapter.defaults();var world=MineAgentRuntimeServices.worldId(server);CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(database())){return db.list(world,SPACE);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((rows,error)->server.execute(()->{if(error!=null){loaded.completeExceptionally(error);return;}try{for(var row:rows){var session=SkillSession.restore(JSON.readValue(row.payload(),SkillSession.Snapshot.class));index(new SkillWork(this,session,row.revision()));}loaded.complete(null);}catch(Exception e){loaded.completeExceptionally(e);}}));}
    public static SkillRuntime get(MinecraftServer server){return ALL.computeIfAbsent(server,SkillRuntime::new);}
    private String policyKey(UUID owner,UUID agent,String actor){return "behavior."+MineAgentRuntimeServices.worldId(server)+"."+actor+"."+(actor.equals("player")?owner:agent)+".combat";}
    public CombatPolicy savedPolicy(UUID owner,UUID agent,String actor){String value=MineAgentRuntimeServices.config(server).snapshot().values().get(policyKey(owner,agent,actor));if(value==null)return CombatPolicy.defaults(SkillSpec.Kind.IDLE,true,"");try{return JSON.readValue(value,CombatPolicy.class);}catch(Exception invalid){throw new IllegalStateException("BEHAVIOR_POLICY_STORAGE_INVALID",invalid);}}
    private void savePolicy(UUID owner,UUID agent,String actor,CombatPolicy policy){try{var cfg=MineAgentRuntimeServices.config(server);var old=cfg.snapshot();String key=policyKey(owner,agent,actor),value=JSON.writeValueAsString(policy);if(!value.equals(old.values().get(key))&&!cfg.apply(new dev.mineagent.runtime.api.config.ConfigPatch(old.revision(),Map.of(key,value)),true).accepted())throw new IllegalStateException("BEHAVIOR_POLICY_SAVE_FAILED");}catch(Exception failed){throw new IllegalStateException("BEHAVIOR_POLICY_SAVE_FAILED",failed);}}
    public static void bodyDied(MineAgentPlayer body){var r=ALL.get(body.level().getServer());if(r==null)return;for(var w:List.copyOf(r.work.values()))if(w.session.agent().equals(body.agentId())&&w.session.spec().actor().equals("ai")&&!w.session.terminal()){
        r.savePolicy(w.session.owner(),w.session.agent(),"ai",w.session.spec().combat());
        if(!w.session.runnable()&&!w.session.reason().equals("BODY_OR_DIMENSION_CHANGED"))continue;
        var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("state","BODY_DIED_REOBSERVE");receipt.put("combatState","BODY_DIED_CANCELLED");receipt.put("previousActionMayHaveApplied",Boolean.toString(w.executed));w.session.receipt(receipt);w.session.waitForRespawn();w.release();r.persist(w);
    }}
    public static void bodyRespawned(MineAgentPlayer body){var r=get(body.level().getServer());r.loaded.thenRunAsync(()->r.resumeRespawn(body),r.server::execute);}
    private void resumeRespawn(MineAgentPlayer body){
        if(!body.canAct()||explicitlyStopped(body.agentId()))return;
        for(var old:List.copyOf(work.values()))if(old.session.agent().equals(body.agentId())&&old.session.spec().actor().equals("ai")&&old.session.reason().equals("WAITING_RESPAWN")&&!old.ioFailed&&old.saved.isDone()&&!old.saved.isCompletedExceptionally()){
            var owner=server.getPlayerList().getPlayer(old.session.owner());if(owner==null||!owner.isAlive()||!ServerTaskStart.allowed(owner,body.agentId())||!old.externalAuthority.getAsBoolean())continue;
            var link=old.session.snapshot();if(link.task()!=null){var task=MineAgentRuntimeServices.tasks(server).get(link.task()).orElse(null);if(task==null||task.intentRevision()!=link.taskIntent()||!Set.of("RUNNING","COMPLETED").contains(task.status().name()))continue;}
            try{
                var spec=old.session.spec();boolean follow=spec.kind()==SkillSpec.Kind.FOLLOW&&(spec.target().equals("$owner")||spec.target().equals(owner.getUUID().toString()));
                if(follow){
                    if(spec.combat().area()!=null&&owner.level()!=body.level()){old.session.transition(State.PAUSED,"RESPAWN_REGION_REQUIRES_RECHECK");persist(old);continue;}
                    var position=respawnNear(body,owner);if(position==null)continue;
                    if(!body.teleportTo(owner.level(),position.x,position.y,position.z,Set.of(),owner.getYRot(),0,true)||body.level()!=owner.level()||body.position().distanceToSqr(position)>.01){old.session.transition(State.PAUSED,"RESPAWN_TELEPORT_REJECTED");persist(old);continue;}body.connection.resetPosition();old.session.followRespawnDimension(body.level().dimension().identifier().toString());old.session.add("followRespawnTeleports",1);
                }
                if(!body.level().dimension().identifier().toString().equals(old.session.spec().dimension())){old.session.transition(State.PAUSED,"RESPAWN_REGION_REQUIRES_RECHECK");persist(old);continue;}
                old.release();UUID actorId=indexedActor.remove(old.token());if(actorId!=null){var list=byEntity.get(actorId);if(list!=null)list.remove(old);}var owned=byOwner.get(old.session.owner());if(owned!=null)owned.remove(old);for(var id:indexedOperations.getOrDefault(old.token(),Set.of()))byOperation.remove(id,old);indexedOperations.remove(old.token());
                var fresh=new SkillWork(this,old.session,old.dbRevision);fresh.externalAuthority=old.externalAuthority;fresh.session.control(fresh.session.revision(),"resume");fresh.session.add("bodyRespawnResumptions",1);fresh.bind(owner);fresh.nextTick=server.getTickCount();index(fresh);persist(fresh);
            }catch(Exception failed){old.session.transition(State.PAUSED,"RESPAWN_RECHECK_FAILED");persist(old);}
        }
    }
    private static Vec3 respawnNear(MineAgentPlayer body,ServerPlayer owner){
        var level=owner.level();for(int dy=0;dy>=-8;dy--)for(int[] offset:new int[][]{{2,0},{-2,0},{0,2},{0,-2},{2,2},{-2,-2}}){var feet=BlockPos.containing(owner.getX()+offset[0],owner.getY()+dy,owner.getZ()+offset[1]);if(!level.hasChunkAt(feet)||level.isOutsideBuildHeight(feet)||!level.getFluidState(feet).isEmpty()||level.getBlockState(feet.below()).getCollisionShape(level,feet.below()).isEmpty())continue;var floor=level.getBlockState(feet.below());var inside=level.getBlockState(feet);if(floor.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)||floor.is(net.minecraft.world.level.block.Blocks.CACTUS)||floor.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)||floor.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)||inside.is(net.minecraft.tags.BlockTags.FIRE)||inside.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE)||inside.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH))continue;var at=Vec3.atBottomCenterOf(feet);if(level.noCollision(body,body.getDimensions(body.getPose()).makeBoundingBox(at)))return at;}
        return null;
    }
    private String defenseKey(UUID agent){return "behavior."+MineAgentRuntimeServices.worldId(server)+"."+agent+".explicitlyStopped";}
    private boolean explicitlyStopped(UUID agent){return Boolean.parseBoolean(MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault(defenseKey(agent),"false"));}
    private void setStopped(UUID agent,boolean stopped){var config=MineAgentRuntimeServices.config(server);var state=config.snapshot();String key=defenseKey(agent),value=Boolean.toString(stopped);if(!value.equals(state.values().get(key))&&!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(state.revision(),Map.of(key,value)),true).accepted())throw new IllegalStateException("BEHAVIOR_STOP_STATE_CHANGED");}
    private final Set<UUID> defensePending=new HashSet<>();
    private void retaliate(MineAgentPlayer body,net.minecraft.world.entity.LivingEntity attacker,boolean damaged){
        if(attacker instanceof net.minecraft.world.entity.player.Player||attacker.isAlliedTo(body)||attacker instanceof net.minecraft.world.entity.OwnableEntity pet&&pet.getOwnerReference()!=null||!body.canAct()||explicitlyStopped(body.agentId())||defensePending.contains(body.agentId()))return;
        // Existing modes (including paused / NONE) remain authoritative; their own local scan handles valid threats.
        if(work.values().stream().anyMatch(w->w.session.agent().equals(body.agentId())&&w.session.spec().actor().equals("ai")))return;
        var owner=server.getPlayerList().getPlayer(body.ownerPlayerId());if(owner==null||!ServerTaskStart.allowed(owner,body.agentId()))return;var defaults=savedPolicy(owner.getUUID(),body.agentId(),"ai");if(defaults.engagement()==CombatPolicy.Engagement.NONE||defaults.engagement()==CombatPolicy.Engagement.SPECIFIED&&!defaults.target().equals(attacker.getUUID().toString()))return;
        defensePending.add(body.agentId());long epoch=BehaviorAuthority.get(server).revision(owner,body.agentId());
        loaded.whenComplete((unused,error)->server.execute(()->{
            if(error!=null){defensePending.remove(body.agentId());return;}
            try{
                if(explicitlyStopped(body.agentId())||work.values().stream().anyMatch(w->w.session.agent().equals(body.agentId())&&w.session.spec().actor().equals("ai"))){defensePending.remove(body.agentId());return;}
                UUID operation=UUID.randomUUID();var args=JSON.createObjectNode().put("id","defense_"+operation.toString().substring(0,8)).put("kind","IDLE").put("actor","ai").put("expected_revision",0).put("dimension",body.level().dimension().identifier().toString());
                if(!body.canAct()||BehaviorAuthority.get(server).revision(owner,body.agentId())!=epoch){defensePending.remove(body.agentId());return;}
                var session=new SkillSession(operation,owner.getUUID(),body.agentId(),MineAgentRuntimeServices.worldId(server),null,SkillSpec.parse(args,null).withCombat(defaults));
                var w=new SkillWork(this,session,0);work.put(operation,w);w.bind(owner);if(damaged)w.lastContactDamage=server.getTickCount();w.session.add(damaged?"automaticRetaliations":"proactiveThreatResponses",1);persist(w);defensePending.remove(body.agentId());

            }catch(Exception failure){defensePending.remove(body.agentId());}
        }));
    }
    private void observeChasers(){
        for(var definition:MineAgentRuntimeServices.bodies(server).definitions()){
            if(Math.floorMod(server.getTickCount()+definition.agentId().hashCode(),6)!=0||explicitlyStopped(definition.agentId())||work.values().stream().anyMatch(w->w.session.agent().equals(definition.agentId())&&w.session.spec().actor().equals("ai")))continue;
            var body=MineAgentRuntimeServices.bodies(server).body(definition.agentId()).orElse(null);if(body==null||!body.canAct())continue;
            for(var mob:body.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,body.getBoundingBox().inflate(16),e->e.isAlive()&&!e.isNoAi()&&e.getTarget()==body)){
                if(mob.isAggressive()||!mob.getNavigation().isDone()||NativeCombatStates.read(mob,body).attacks().stream().anyMatch(NativeCombatStates.Attack::running)){retaliate(body,mob,false);break;}
            }
        }
    }
    /** A player's inventory edit may end a use animation, but never discards the combat/work intent. */
    public static void inventoryEdited(MineAgentPlayer body){
        body.stopUsingItem();var runtime=ALL.get(body.level().getServer());if(runtime==null)return;
        for(var w:runtime.forEntity(body))if(w.actor!=null&&w.session.runnable()){
            w.actor.stop(w.token());w.combatOperation=w.shieldOperation=w.healingOperation=w.extensionOperation=null;
            w.combatStage=0;w.healingWasUsing=false;w.combat.nextScan=0;w.nextTick=runtime.server.getTickCount();
        }
    }
    public static boolean taskActive(MinecraftServer server,UUID task){var r=ALL.get(server);return r!=null&&r.work.values().stream().anyMatch(w->task.equals(w.session.snapshot().task())&&!w.session.terminal());}
    public static boolean following(MinecraftServer server,UUID agent){var r=ALL.get(server);return r!=null&&r.work.values().stream().anyMatch(w->w.session.agent().equals(agent)&&w.session.spec().actor().equals("ai")&&w.session.spec().kind()==SkillSpec.Kind.FOLLOW&&w.session.runnable());}
    public static void legacyFollow(ServerPlayer owner,UUID agent){var r=get(owner.level().getServer());if(!r.loaded.isDone()||r.loaded.isCompletedExceptionally()||r.legacyPending.contains(agent)||r.work.values().stream().anyMatch(w->w.session.agent().equals(agent)&&w.session.spec().actor().equals("ai")&&!w.session.terminal()))return;r.legacyPending.add(agent);var args=JSON.createObjectNode().put("id","owner_follow").put("expected_revision",0).put("dimension",owner.level().dimension().identifier().toString()).put("target","$owner");r.start(owner,agent,UUID.randomUUID(),null,args,SkillSpec.Kind.FOLLOW,()->Boolean.parseBoolean(MineAgentRuntimeServices.config(r.server).snapshot().values().getOrDefault("agent."+agent+".follow","false"))).whenComplete((v,e)->r.server.execute(()->r.legacyPending.remove(agent)));}
    private java.nio.file.Path database(){return server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");}
    private void authorize(ServerPlayer p,UUID agent){if(server.getPlayerList().getPlayer(p.getUUID())!=p||!ServerTaskStart.allowed(p,agent))throw new SecurityException("SKILL_PERMISSION");}
    private void validateCombat(ServerPlayer p,UUID agent,CombatPolicy policy,String actor){
        if(policy.engagement()==CombatPolicy.Engagement.SPECIFIED){var e=p.level().getEntity(UUID.fromString(policy.target()));if(!(e instanceof net.minecraft.world.entity.LivingEntity))throw new IllegalArgumentException("COMBAT_TARGET_NOT_OBSERVED");if(e instanceof ServerPlayer target){var body=actor.equals("player")?p:MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow();if(!PvpConsent.allowed(p,agent,body,target,policy))throw new SecurityException("COMBAT_EXPLICIT_PVP_TARGET_REQUIRED");}
            else if(e instanceof net.minecraft.world.entity.OwnableEntity own&&own.getOwnerReference()!=null||e.isAlliedTo(p))throw new SecurityException("COMBAT_FRIENDLY_TARGET");}
        if(policy.engagement()==CombatPolicy.Engagement.PROTECT&&!policy.protect().equals("$owner")&&!(p.level().getEntity(UUID.fromString(policy.protect())) instanceof net.minecraft.world.entity.LivingEntity))throw new IllegalArgumentException("COMBAT_PROTECTED_TARGET_NOT_OBSERVED");
    }
    static boolean attackAllowed(SkillWork w,net.minecraft.world.entity.LivingEntity entity){
        if(!entity.isAlive()||entity.level()!=w.player().level())return false;var rule=w.session.spec().combat();if(rule.excluded().contains(entity.getUUID()))return false;
        if(entity instanceof ServerPlayer target){var owner=w.runtime.server.getPlayerList().getPlayer(w.session.owner());return owner!=null&&PvpConsent.allowed(owner,w.session.agent(),w.player(),target,rule);}
        return !(entity instanceof net.minecraft.world.entity.player.Player)&&!entity.isAlliedTo(w.player())&&!(entity instanceof net.minecraft.world.entity.OwnableEntity pet&&pet.getOwnerReference()!=null);
    }
    public boolean requiresPlayerAuthorization(ServerPlayer p,UUID agent,String tool,JsonNode n){
        if(SkillTools.START.contains(tool))return n.path("actor").asText("ai").equals("player");
        if(Set.of("control_skill","control_behavior").contains(tool)&&n.path("action").asText().equals("resume"))try{return selected(p,agent,n.path("id").asText()).session.spec().actor().equals("player");}catch(IllegalStateException missing){return false;}
        return false;
    }
    public CompletableFuture<Map<String,Object>> execute(ServerPlayer p,UUID agent,UUID operation,UUID task,String tool,JsonNode n,BooleanSupplier permit){
        if(!permit.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("SKILL_CONTEXT_CHANGED"));
        return switch(tool){case "inspect_skills","inspect_behavior"->inspect(p,agent);case "control_skill","control_behavior"->control(p,agent,n,permit);case "set_combat_policy"->policy(p,agent,n,permit);default->start(p,agent,operation,task,n,SkillTools.kind(tool),permit);};
    }
    public Map<String,Object> snapshot(ServerPlayer p,UUID agent){authorize(p,agent);return inspect(p,agent).getNow(Map.of("status","LOADING","skills",List.of()));}
    private SkillWork selected(ServerPlayer p,UUID agent,String id){return java.util.stream.Stream.concat(history.values().stream(),work.values().stream()).filter(w->w.session.owner().equals(p.getUUID())&&w.session.agent().equals(agent)&&(w.session.spec().id().equals(id)||w.token().toString().equals(id))).reduce((a,b)->b).orElseThrow(()->new IllegalStateException("SKILL_NOT_FOUND"));}
    public CompletableFuture<Map<String,Object>> policy(ServerPlayer p,UUID agent,JsonNode n){return policy(p,agent,n,()->true);}
    public CompletableFuture<Map<String,Object>> policy(ServerPlayer p,UUID agent,JsonNode n,BooleanSupplier permit){authorize(p,agent);return loaded.thenCompose(v->{try{authorize(p,agent);if(!permit.getAsBoolean())throw new IllegalStateException("SKILL_CONTEXT_CHANGED");var w=selected(p,agent,n.path("id").asText());var policy=CombatPolicy.parse(n.get("combat"),w.session.spec().combat());validateCombat(p,agent,policy,w.session.spec().actor());w.session.combat(n.path("expected_revision").asLong(),policy);savePolicy(p.getUUID(),agent,w.session.spec().actor(),policy);CombatSkill.policyChanged(w);persist(w);for(UUID priorId=w.session.previous();priorId!=null;){var prior=work.get(priorId);if(prior==null||prior.session.terminal()||!prior.session.reason().equals("TEMPORARY_WORK"))break;var patched=CombatPolicy.parse(n.get("combat"),prior.session.spec().combat());validateCombat(p,agent,patched,prior.session.spec().actor());prior.session.combat(prior.session.revision(),patched);CombatSkill.policyChanged(prior);persist(prior);priorId=prior.session.previous();}var reply=new CompletableFuture<Map<String,Object>>();w.saved.whenComplete((x,error)->server.execute(()->{if(error!=null)reply.completeExceptionally(error);else reply.complete(Map.of("status","APPLIED","workPreserved",true,"skill",w.view()));}));return reply;}catch(Exception e){return CompletableFuture.failedFuture(e);}});}
    public boolean stopAll(ServerPlayer p,UUID agent){authorize(p,agent);setStopped(agent,true);PvpConsent.revoke(p,agent);int constructionStopped=dev.mineagent.runtime.neoforge.ui.ServerBuildings.pauseForAgent(p,agent)+dev.mineagent.runtime.neoforge.ui.ConversationWorldGeometry.cancelForAgent(p,agent);BehaviorAuthority.get(server).invalidate(p,agent);if(agent.equals(AutonomousPlayerAgent.inspect(p).get("agent")))AutonomousPlayerAgent.stopForPlayer(p);MineAgentRuntimeServices.bodies(server).body(agent).ifPresent(body->{body.controls().cancel();body.movementController().stop();body.stopUsingItem();});boolean changed=constructionStopped>0;for(var w:List.copyOf(work.values()))if(w.session.owner().equals(p.getUUID())&&w.session.agent().equals(agent)&&!w.session.terminal()){w.session.control(w.session.revision(),"stop");w.release();if(w.actor instanceof PlayerSkillActor)AutonomousPlayerAgent.endLocalSkill(p,w.token());persist(w);changed=true;}for(var task:MineAgentRuntimeServices.tasks(server).all())if(task.ownerPlayerId().equals(p.getUUID())&&task.agentId().equals(agent)&&Set.of("RUNNING","PAUSED","WAITING_FOR_PLAYER").contains(task.status().name()))try{MineAgentRuntimeServices.tasks(server).transition(task.taskId(),task.revision(),true,dev.mineagent.runtime.api.task.TaskStatus.CANCELLED);}catch(Exception failure){throw new IllegalStateException("TASK_STOP_PERSISTENCE_FAILED",failure);}var config=MineAgentRuntimeServices.config(server);var state=config.snapshot();if(Boolean.parseBoolean(state.values().getOrDefault("agent."+agent+".follow","false")))config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(state.revision(),Map.of("agent."+agent+".follow","false")),true);dev.mineagent.runtime.neoforge.ui.ServerConversations.stopBehaviorRequests(p,agent);return changed;}
    void resumePrevious(SkillWork finished){
        if(finished.session.previous()==null){standbyAfterCompletion(finished);return;}var prior=work.get(finished.session.previous());if(prior==null||prior.session.terminal()||!prior.session.reason().equals("TEMPORARY_WORK"))return;
        try{var owner=server.getPlayerList().getPlayer(prior.session.owner());if(owner==null)return;authorize(owner,prior.session.agent());prior.session.control(prior.session.revision(),"resume");prior.bind(owner);persist(prior);}catch(Exception reason){prior.pause("PREVIOUS_WORK_REQUIRES_RECHECK");}
    }
    private void standbyAfterCompletion(SkillWork finished){
        var owner=server.getPlayerList().getPlayer(finished.session.owner());if(owner==null)return;
        long revision=BehaviorAuthority.get(server).revision(owner,finished.session.agent());
        finished.saved.whenComplete((saved,error)->server.execute(()->{
            if(error!=null||!owner.isAlive()||!finished.externalAuthority.getAsBoolean()||finished.session.state()!=State.COMPLETED||finished.actor==null||!finished.actor.current()||BehaviorAuthority.get(server).revision(owner,finished.session.agent())!=revision)return;
            if(work.values().stream().anyMatch(w->w!=finished&&w.session.owner().equals(owner.getUUID())&&w.session.agent().equals(finished.session.agent())&&w.session.spec().actor().equals(finished.session.spec().actor())&&!w.session.terminal()))return;
            try{
                authorize(owner,finished.session.agent());var previous=finished.session.spec();UUID id=UUID.randomUUID();
                var spec=new SkillSpec("standby_"+id.toString().substring(0,8),SkillSpec.Kind.IDLE,previous.actor(),previous.dimension(),"",null,List.of(),false,previous.defend(),false,false,false,4,2,20,0,"",previous.combat(),false);
                var session=new SkillSession(id,owner.getUUID(),finished.session.agent(),MineAgentRuntimeServices.worldId(server),null,spec);
                session.receipt(Map.of("completedWork",finished.token().toString(),"state","STANDBY_AFTER_COMPLETION"));
                var next=new SkillWork(this,session,0);next.externalAuthority=finished.externalAuthority;work.put(id,next);next.bind(owner);persist(next);
            }catch(Exception failure){finished.notice("standby_failed","任务已完成，待命需要重新设置。");}
        }));
    }
    public void pauseFromTakeover(ServerPlayer p,UUID skill,boolean pause)throws Exception{
        var w=work.get(skill);if(w==null||w.session.terminal())return;authorize(p,w.session.agent());if(!w.session.owner().equals(p.getUUID())||!w.session.spec().actor().equals("player"))throw new SecurityException("SKILL_PLAYER_OWNER");
        if(pause&&w.session.runnable()){
            if(w.operation!=null&&!w.executed)w.confirm("NOT_EXECUTED_USER_PAUSE",Map.of());
            w.session.control(w.session.revision(),"pause");w.release();persist(w);
        }else if(!pause&&w.session.state()==State.PAUSED){w.session.control(w.session.revision(),"resume");w.bind(p);w.nextTick=server.getTickCount();persist(w);}
    }
    public CompletableFuture<Map<String,Object>> inspect(ServerPlayer p,UUID agent){authorize(p,agent);return loaded.thenApply(v->{authorize(p,agent);return Map.of("status","OBSERVED","skills",java.util.stream.Stream.concat(history.values().stream().skip(Math.max(0,history.size()-16)),work.values().stream()).filter(w->w.session.owner().equals(p.getUUID())&&w.session.agent().equals(agent)).map(SkillWork::view).toList(),"kinds",Arrays.stream(SkillSpec.Kind.values()).map(Enum::name).toList(),"actors",List.of("ai","player"),"cropAdapters",CropAdapter.adapters().stream().map(CropAdapter::id).toList(),"lifecycle","RUNNING/WAITING/SUSPENDED/PAUSED/COMPLETED/FAILED/CANCELLED; STARTED is not goal completion");});}
    public CompletableFuture<Map<String,Object>> history(ServerPlayer p,UUID agent,int offset){
        authorize(p,agent);if(offset<0)throw new IllegalArgumentException("SKILL_HISTORY_OFFSET");UUID world=MineAgentRuntimeServices.worldId(server),owner=p.getUUID();var reply=new CompletableFuture<Map<String,Object>>();
        CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(database())){
            var rows=new ArrayList<SkillSession.Snapshot>();for(var row:db.list(world,SPACE)){var value=JSON.readValue(row.payload(),SkillSession.Snapshot.class);if(value.owner().equals(owner)&&value.agent().equals(agent)&&Set.of(State.COMPLETED,State.FAILED,State.CANCELLED).contains(value.state()))rows.add(value);}
            return Map.<String,Object>of("status","OBSERVED","historical",true,"skills",rows.stream().skip(offset).limit(16).toList(),"total",rows.size(),"nextOffset",offset+16<rows.size()?offset+16:-1);
        }catch(Exception error){throw new CompletionException(error);}},IO).whenComplete((value,error)->server.execute(()->{try{authorize(p,agent);if(error!=null)reply.completeExceptionally(error);else reply.complete(value);}catch(Exception denied){reply.completeExceptionally(denied);}}));return reply;
    }
    public CompletableFuture<Map<String,Object>> start(ServerPlayer p,UUID agent,UUID operation,UUID task,JsonNode arguments,SkillSpec.Kind alias,BooleanSupplier permit){
        authorize(p,agent);long arrivalRevision=BehaviorAuthority.get(server).revision(p,agent);var spec=SkillSpec.parse(arguments,alias);validateCombat(p,agent,spec.combat(),spec.actor());if(!spec.dimension().equals(p.level().dimension().identifier().toString()))throw new IllegalArgumentException("SKILL_DIMENSION");
        if(!arguments.path("expected_revision").isIntegralNumber()||arguments.path("expected_revision").asLong()!=0)throw new IllegalArgumentException("SKILL_CREATE_REVISION_ZERO");
        return loaded.thenCompose(v->known(operation)).thenComposeAsync(existing->{try{authorize(p,agent);if(!permit.getAsBoolean())throw new IllegalStateException("SKILL_CONTEXT_CHANGED");
            if(BehaviorAuthority.get(server).revision(p,agent)!=arrivalRevision)throw new IllegalStateException("SKILL_CONTEXT_CHANGED");
            if(existing!=null)return CompletableFuture.completedFuture(existing.view());
            if(arguments.path("only_if_idle").asBoolean()&&work.values().stream().anyMatch(w->w.session.owner().equals(p.getUUID())&&w.session.agent().equals(agent)&&w.session.spec().actor().equals(spec.actor())&&!w.session.terminal()))throw new IllegalStateException("BEHAVIOR_STATE_CHANGED");
            if(work.values().stream().anyMatch(w->w.session.owner().equals(p.getUUID())&&w.session.agent().equals(agent)&&w.session.spec().id().equals(spec.id())&&!w.session.terminal()))throw new IllegalStateException("SKILL_ID_ALREADY_ACTIVE");
            if(spec.allowTeleport()&&!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("SKILL_TELEPORT_PERMISSION");
            if(spec.actor().equals("ai")&&!(spec.kind()==SkillSpec.Kind.FOLLOW&&spec.target().equals("$owner"))){var config=MineAgentRuntimeServices.config(server);var values=config.snapshot();String key="agent."+agent+".follow";if(Boolean.parseBoolean(values.values().getOrDefault(key,"false"))&&!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(values.revision(),Map.of(key,"false")),true).accepted())throw new IllegalStateException("SKILL_FOLLOW_CONFIG_CHANGED");}
            if(!spec.target().isBlank()&&!spec.target().equals("$owner")){var entity=p.level().getEntity(UUID.fromString(spec.target()));if(entity==null)throw new IllegalStateException("SKILL_TARGET_NOT_OBSERVED");}
            SkillWork previous=null;CombatPolicy inherited=savedPolicy(p.getUUID(),agent,spec.actor());
            for(var old:List.copyOf(work.values()))if(old.session.owner().equals(p.getUUID())&&old.session.agent().equals(agent)&&old.session.spec().actor().equals(spec.actor())){
                inherited=old.session.spec().combat();if(!old.session.terminal()){
                    if(spec.resumePrevious()&&old.session.runnable()){old.session.control(old.session.revision(),"pause");old.session.transition(State.PAUSED,"TEMPORARY_WORK");previous=old;}
                    else if(!spec.resumePrevious()){old.session.control(old.session.revision(),"stop");UUID parent=old.session.snapshot().task();if(parent!=null&&!Objects.equals(parent,task)){var managed=MineAgentRuntimeServices.tasks(server).get(parent).orElse(null);if(managed!=null&&Set.of("RUNNING","PAUSED","WAITING_FOR_PLAYER").contains(managed.status().name()))MineAgentRuntimeServices.tasks(server).transition(parent,managed.revision(),true,dev.mineagent.runtime.api.task.TaskStatus.CANCELLED);}}
                    old.release();persist(old);
                }
            }
            var effective=spec;
            if(inherited!=null){var prior=arguments.has("defend")||spec.kind()==SkillSpec.Kind.COMBAT&&!spec.target().isBlank()?new CombatPolicy(inherited.strategy(),spec.combat().engagement(),spec.combat().protect(),inherited.excluded(),inherited.leash(),inherited.awareness(),spec.combat().target(),spec.combat().area()):inherited;effective=spec.withCombat(CombatPolicy.parse(arguments.get("combat"),prior));}
            validateCombat(p,agent,effective.combat(),effective.actor());savePolicy(p.getUUID(),agent,effective.actor(),effective.combat());var linked=task==null?null:MineAgentRuntimeServices.tasks(server).get(task).orElseThrow();var session=new SkillSession(operation,p.getUUID(),agent,MineAgentRuntimeServices.worldId(server),task,linked==null?0:linked.intentRevision(),effective);if(previous!=null)session.previous(previous.token());var w=new SkillWork(this,session,0);w.starting=true;work.put(operation,w);persist(w);
            var result=new CompletableFuture<Map<String,Object>>();w.saved.whenComplete((written,error)->server.execute(()->{try{if(error!=null)throw new CompletionException(error);if(session.terminal()){result.complete(Map.of("status",session.state().name(),"skill",w.view(),"goalComplete",false));return;}authorize(p,agent);if(!permit.getAsBoolean()){session.transition(State.PAUSED,"START_CONTEXT_CHANGED");persist(w);}else {if(session.spec().actor().equals("ai"))setStopped(agent,false);w.bind(p);}result.complete(Map.of("status",session.runnable()?"STARTED":session.state().name(),"skill",w.view(),"goalComplete",false));}catch(Exception failure){if(!session.terminal()){session.transition(State.PAUSED,"START_FAILED");persist(w);}result.completeExceptionally(failure);}finally{w.starting=false;}}));return result;
        }catch(Exception failure){return CompletableFuture.failedFuture(failure);}},server::execute);
    }
    public CompletableFuture<Map<String,Object>> control(ServerPlayer p,UUID agent,JsonNode n){return control(p,agent,n,()->true);}
    public CompletableFuture<Map<String,Object>> control(ServerPlayer p,UUID agent,JsonNode n,BooleanSupplier permit){authorize(p,agent);return loaded.thenCompose(v->{try{
        authorize(p,agent);if(!permit.getAsBoolean())throw new IllegalStateException("SKILL_CONTEXT_CHANGED");String id=n.path("id").asText();var w=selected(p,agent,id);
        if(!n.path("expected_revision").isIntegralNumber())throw new IllegalArgumentException("SKILL_REVISION");String action=n.path("action").asText();
        if(action.equals("update")){
            if(w.operation!=null||w.combatOperation!=null)return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","SKILL_ACTION_STILL_PENDING","executionState","NOT_STARTED","suggestedAction","Observe the pending native action; adjust only after its known receipt, or reconcile uncertainty."));
            SkillSpec adjusted;try{adjusted=w.session.spec().adjust(n.path("patch"));if(!adjusted.target().isBlank()&&!adjusted.target().equals("$owner")&&(w.actor==null?p:w.player()).level().getEntity(UUID.fromString(adjusted.target()))==null)throw new IllegalArgumentException("SKILL_TARGET_NOT_OBSERVED");w.session.adjust(n.path("expected_revision").asLong(),adjusted);}catch(IllegalArgumentException|IllegalStateException invalid){return CompletableFuture.completedFuture(Map.of("status","REJECTED","error",Objects.toString(invalid.getMessage(),"SKILL_ADJUST_INVALID"),"executionState","NOT_STARTED","worldModified",false));}
            if(w.hook!=null)FishSkill.interrupt(w);w.release();w.abandonTarget();w.wanderTarget=null;w.wanderSearch=null;w.nextTick=server.getTickCount();
        }
        else if(action.equals("reconcile")){w.release();var body=w.session.spec().actor().equals("player")?p:MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow();var observed=new LinkedHashMap<String,String>();observed.put("observedActorEntityId",Integer.toString(body.getId()));observed.put("observedPosition",body.position().toString());observed.put("observedMainHand",body.getMainHandItem().toString());observed.put("observedHook",body.fishing==null?"":body.fishing.getUUID().toString());String coordinate=w.session.receipt().get("block");if(coordinate!=null){var xyz=coordinate.split(",\\s*");if(xyz.length==3){var pos=new BlockPos(Integer.parseInt(xyz[0].trim()),Integer.parseInt(xyz[1].trim()),Integer.parseInt(xyz[2].trim()));if(!body.level().hasChunkAt(pos))throw new IllegalStateException("SKILL_RECONCILE_CHUNK_UNLOADED");observed.put("observedBlock",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(body.level().getBlockState(pos)));}}String item=w.session.receipt().get("seed");if(item!=null){var nativeItem=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item));observed.put("observedSeedCount",Integer.toString(body.getInventory().countItem(nativeItem)));}w.session.reconcile(n.path("expected_revision").asLong(),observed);}
        else {if(action.equals("stop")&&w.session.spec().actor().equals("ai")){setStopped(agent,true);PvpConsent.revoke(p,agent);}w.session.control(n.path("expected_revision").asLong(),action);w.release();if(action.equals("resume"))w.bind(p);if(action.equals("pause")&&w.actor instanceof PlayerSkillActor)AutonomousPlayerAgent.control(p,agent,"pause","");if(action.equals("stop")&&w.actor instanceof PlayerSkillActor)AutonomousPlayerAgent.endLocalSkill(p,w.token());}
        persist(w);var reply=new CompletableFuture<Map<String,Object>>();w.saved.whenComplete((x,error)->server.execute(()->{if(error!=null)reply.completeExceptionally(error);else reply.complete(Map.of("status","APPLIED","skill",w.view()));}));return reply;
    }catch(Exception failure){return CompletableFuture.failedFuture(failure);}});}
    void persist(SkillWork w){index(w);try{String payload=JSON.writeValueAsString(w.session.snapshot());UUID world=MineAgentRuntimeServices.worldId(server);w.saved=w.saved.thenRunAsync(()->{try(var db=new SqliteRuntimeRepository(database())){var result=db.compareAndSet(world,SPACE,w.session.id().toString(),w.dbRevision,payload,System.currentTimeMillis());if(!result.accepted())throw new IllegalStateException("SKILL_JOURNAL_CONFLICT");w.dbRevision++;}catch(Exception e){w.ioFailed=true;throw new CompletionException(e);}},IO);}catch(Exception e){w.ioFailed=true;}}
    boolean scan(){if(scanBudget<=0||System.nanoTime()>=deadline)return false;scanBudget--;return true;}
    private void tick(){if(!loaded.isDone()||loaded.isCompletedExceptionally())return;observeChasers();if(server.getTickCount()%20==0)for(var definition:MineAgentRuntimeServices.bodies(server).definitions())MineAgentRuntimeServices.bodies(server).body(definition.agentId()).ifPresent(this::resumeRespawn);scanBudget=384;deadline=System.nanoTime()+4_000_000;reservations.expire(server.getTickCount());
        for(var w:work.values().stream().filter(w->w.session.runnable()).sorted(Comparator.comparingLong(a->a.lastTick)).toList()){
            if(System.nanoTime()>=deadline)break;if(!w.session.runnable()||w.starting)continue;w.lastTick=server.getTickCount();
            try{if(w.ioFailed){w.pause("JOURNAL_UNKNOWN_RECONCILE");continue;}var owner=server.getPlayerList().getPlayer(w.session.owner());if(owner==null||!owner.isAlive()||!ServerTaskStart.allowed(owner,w.session.agent())||!w.externalAuthority.getAsBoolean()){if(w.session.spec().actor().equals("player")){w.session.control(w.session.revision(),"stop");w.release();if(owner!=null)AutonomousPlayerAgent.endLocalSkill(owner,w.token());persist(w);}else w.pause("OWNER_OR_PERMISSION_CHANGED");continue;}
                var link=w.session.snapshot();if(link.task()!=null){var task=MineAgentRuntimeServices.tasks(server).get(link.task()).orElse(null);if(task==null||task.intentRevision()!=link.taskIntent()||Set.of("CANCELLED","FAILED").contains(task.status().name())){w.session.control(w.session.revision(),"stop");w.release();persist(w);continue;}if(!Set.of("RUNNING","COMPLETED").contains(task.status().name())){w.waitFor("PARENT_TASK_PAUSED",10);continue;}}
                if(w.actor==null){if(!w.saved.isDone())continue;w.bind(owner);if(w.actor==null)continue;}if(!w.actor.current()||!w.actor.player().level().dimension().identifier().toString().equals(w.session.spec().dimension())){if(w.actor instanceof PlayerSkillActor){w.session.control(w.session.revision(),"stop");w.release();persist(w);}else w.pause("BODY_OR_DIMENSION_CHANGED");continue;}
                w.actor.controls().validate();if(!w.actor.inputReady()){w.release();w.session.transition(State.WAITING,"INPUT_OR_MENU_PAUSED");continue;}
                if(CombatSkill.interruptOrContinue(w))continue;
                if(!w.saved.isDone()||server.getTickCount()<w.nextTick)continue;
                if(!w.acquire())continue;w.step();
            }catch(Exception failure){w.pause("SKILL_ERROR_"+Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));}
        }
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var runtime=ALL.get(e.getServer());if(runtime!=null)runtime.tick();}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent e){PvpConsent.stopped(e.getServer());var r=ALL.remove(e.getServer());if(r!=null){for(var w:List.copyOf(r.work.values())){if(!w.session.terminal()){if(w.session.state()==State.PAUSED&&Set.of("TEMPORARY_WORK","WAITING_RESPAWN").contains(w.session.reason())){w.release();r.persist(w);}else w.pause("SERVER_RESTART");}}try{CompletableFuture.allOf(java.util.stream.Stream.concat(r.work.values().stream(),r.history.values().stream()).map(w->w.saved).toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);}catch(Exception ignored){}}}
    public static boolean cancelForBody(ServerPlayer p,UUID agent){var r=ALL.get(p.level().getServer());if(r==null)return false;boolean cancelled=false;for(var w:List.copyOf(r.work.values()))if(w.session.owner().equals(p.getUUID())&&w.session.agent().equals(agent)&&w.session.spec().actor().equals("ai")&&!w.session.terminal()){w.session.control(w.session.revision(),"stop");w.release();r.persist(w);cancelled=true;}return cancelled;}
    public static void cancelPlayerSession(ServerPlayer p,UUID skill,String reason){
        var r=ALL.get(p.level().getServer());if(r==null)return;var w=r.work.get(skill);
        if(w==null||w.session.terminal()||!w.session.owner().equals(p.getUUID())||!w.session.spec().actor().equals("player"))return;
        w.session.control(w.session.revision(),"stop");w.session.transition(State.CANCELLED,reason);w.release();r.persist(w);
    }
    public static void nativeBreak(ServerPlayer player,BlockPos position,boolean removed){NativeTerrainRecovery.nativeBreak(player,position,removed);var r=ALL.get(player.level().getServer());if(r==null||!removed)return;for(var w:r.forEntity(player))if(w.actor!=null&&w.actor.player()==player&&w.session.runnable()&&w.executed&&w.operation!=null&&w.action.equals("HARVEST_BREAK")&&position.equals(w.block))w.nativeBreak=true;}
    public static boolean observingUse(ServerPlayer player,BlockPos position){if(NativeTerrainRecovery.observingUse(player,position))return true;var r=ALL.get(player.level().getServer());return r!=null&&r.forEntity(player).stream().anyMatch(w->w.actor!=null&&w.actor.player()==player&&w.session.runnable()&&w.operation!=null&&w.executed&&w.block!=null&&(position.equals(w.block)||position.equals(w.block.below())));}
    public static void nativeUse(ServerPlayer player,BlockPos pos,net.minecraft.world.item.ItemStack before,net.minecraft.world.item.ItemStack after,boolean accepted){NativeTerrainRecovery.nativeUse(player,pos,before,after,accepted);var r=ALL.get(player.level().getServer());if(r==null)return;for(var w:r.forEntity(player))if(w.actor!=null&&w.actor.player()==player&&w.session.runnable()&&w.operation!=null&&w.executed&&w.block!=null&&(pos.equals(w.block)||pos.equals(w.block.below()))){w.nativeUse=accepted;w.nativeConsumed=before.getCount()-(after.isEmpty()?0:after.getCount());w.nativeDurability=after.isEmpty()?before.getMaxDamage()-before.getDamageValue():after.getDamageValue()-before.getDamageValue();}}
    public void attachScriptAuthority(UUID operation,BooleanSupplier authority){var w=work.get(operation);if(w!=null)w.externalAuthority=authority;}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) public static void projectile(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){if(event.isCanceled()||event.loadedFromDisk()||!(event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile shot)||!(shot.getOwner() instanceof ServerPlayer player))return;var r=ALL.get(player.level().getServer());if(r==null)return;for(var w:r.forEntity(player)){
        if(w.actor==null||w.actor.player()!=player||!w.actor.current()||!w.session.runnable()||!w.actor.controls().owns(w.token(),BodyDomain.MAIN_HAND))continue;
        // The last throwable can leave the inventory before its network spawn is observed. Tie the receipt
        // to the issued action instead of requiring the controller to still be in its release phase.
        UUID operation=w.ranged.issuedOperation!=null&&r.server.getTickCount()-w.ranged.issuedAt<=40?w.ranged.issuedOperation:w.combatStage==2?w.combatOperation:null;
        if(operation==null)continue;w.session.add("nativeProjectilesSpawned",1);shot.getPersistentData().putString("mineagent_skill_session",w.token().toString());shot.getPersistentData().putString("mineagent_skill_operation",operation.toString());
    }}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void projectileContact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event){
        if(event.isCanceled()||!(event.getProjectile().getOwner() instanceof ServerPlayer player)||!(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit))return;
        var runtime=ALL.get(player.level().getServer());if(runtime==null)return;String session=event.getProjectile().getPersistentData().getStringOr("mineagent_skill_session","");
        for(var work:runtime.forEntity(player))if(work.token().toString().equals(session)&&hit.getEntity().getUUID().equals(work.fighting))work.session.add("nativeProjectileTargetContacts",1);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void critical(net.neoforged.neoforge.event.entity.player.CriticalHitEvent event){
        if(!event.isCriticalHit()||!(event.getEntity() instanceof ServerPlayer player))return;
        var runtime=ALL.get(player.level().getServer());if(runtime==null)return;
        for(var w:runtime.forEntity(player))if(w.session.runnable()){w.observedCriticalTick=runtime.server.getTickCount();w.observedCriticalTarget=event.getTarget().getUUID();w.session.add(ActorEnhancements.boost(player)?"boostCriticalAttempts":"nativeCriticalAttempts",1);if(ActorEnhancements.boost(player)&&BoostRuntime.microHopObserved(player))w.session.add("boostMicroHopHeightObserved",1);}
    }
    @SubscribeEvent public static void damage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event){
        if(event.getHealthDamage()>0&&event.getEntity() instanceof ServerPlayer owner){var runtime=ALL.get(owner.level().getServer());if(runtime!=null)for(var active:List.copyOf(runtime.byOwner.getOrDefault(owner.getUUID(),new LinkedHashSet<>())))if(active.session.runnable()&&active.actor!=null&&active.actor.current()){active.combat.nextScan=0;active.lastCombatTick=-1;}}

        if(event.getHealthDamage()>0&&event.getEntity() instanceof MineAgentPlayer body&&event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker&&!(attacker instanceof net.minecraft.world.entity.player.Player))get(body.level().getServer()).retaliate(body,attacker,true);
        if(event.getHealthDamage()>0&&event.getEntity() instanceof ServerPlayer defender&&event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker){var runtime=ALL.get(defender.level().getServer());if(runtime!=null)for(var active:runtime.forEntity(defender))if(active.actor!=null&&active.actor.player()==defender&&active.session.runnable()){active.lastContactDamage=runtime.server.getTickCount();active.session.add("nativeDamageEvents",1);active.session.add("nativeDamageTakenMilli",(long)(event.getHealthDamage()*1000));}}if(!(event.getSource().getEntity() instanceof ServerPlayer player)||event.getHealthDamage()<=0)return;var r=ALL.get(player.level().getServer());if(r==null)return;var direct=event.getSource().getDirectEntity();boolean shot=direct instanceof net.minecraft.world.entity.projectile.Projectile;String session=shot?direct.getPersistentData().getStringOr("mineagent_skill_session",""):"";for(var w:r.forEntity(player))if(w.actor!=null&&w.actor.player()==player&&w.fighting!=null&&w.fighting.equals(event.getEntity().getUUID())&&(shot?w.token().toString().equals(session):w.session.runnable()&&w.actor.controls().owns(w.token(),BodyDomain.MAIN_HAND))){if(!shot){int now=r.server.getTickCount();w.comboStreak=event.getEntity().getUUID().equals(w.lastMeleeHitTarget)&&now-w.lastMeleeHitTick<=40?w.comboStreak+1:1;w.lastMeleeHitTarget=event.getEntity().getUUID();w.lastMeleeHitTick=now;w.session.add("maxMeleeCombo",Math.max(0,w.comboStreak-w.session.count("maxMeleeCombo")));if(player.isSprinting())w.session.add("nativeSprintMeleeHits",1);}w.lastHitAt=r.server.getTickCount();w.session.add("verifiedHits",1);if(w.observedCriticalTick==r.server.getTickCount()&&event.getEntity().getUUID().equals(w.observedCriticalTarget))w.session.add(ActorEnhancements.boost(player)?"verifiedBoostCriticalHits":"verifiedNativeCriticalHits",1);w.session.add("damageMilliHearts",(long)(event.getHealthDamage()*1000));if(shot&&!direct.isNoGravity())w.session.add("nativeGravityHits",1);if(w.session.terminal())r.persist(w);}}
    @SubscribeEvent public static void pickedUp(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event){
        if(!(event.getPlayer() instanceof ServerPlayer player))return;var r=ALL.get(player.level().getServer());if(r==null)return;
        int count=event.getOriginalStack().getCount()-event.getCurrentStack().getCount();if(count<=0)return;
        for(var w:r.forEntity(player))if(w.actor!=null&&w.actor.player()==player&&w.session.runnable()){
            UUID id=event.getItemEntity().getUUID();boolean owned=w.loot.containsKey(id)||w.operation!=null&&(w.action.startsWith("HARVEST")||w.action.equals("FISH_REEL"))&&!w.dropBefore.contains(id)&&w.block!=null&&event.getItemEntity().position().distanceToSqr(Vec3.atCenterOf(w.block))<64;
            if(!owned)continue;w.session.add("lootPickedUp",count);w.pickedDrops.add(id);if(event.getCurrentStack().isEmpty())w.loot.remove(id);w.lootRetries=0;w.blockedLootTerrain=null;r.persist(w);
        }
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) public static void shieldBlocked(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event){
        if(!(event.getEntity() instanceof ServerPlayer p)||!event.getOriginalBlock()||!event.getBlocked()||event.getBlockedDamage()<=0)return;
        var r=ALL.get(p.level().getServer());if(r==null)return;
        for(var w:r.forEntity(p))if(w.actor!=null&&w.actor.player()==p&&w.session.runnable()&&w.actor.controls().owns(w.token(),BodyDomain.MAIN_HAND))w.session.add("nativeShieldBlocks",1);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) public static void fished(net.neoforged.neoforge.event.entity.player.ItemFishedEvent event){if(event.isCanceled()||!(event.getEntity() instanceof ServerPlayer p))return;var r=ALL.get(p.level().getServer());if(r==null)return;for(var w:r.forEntity(p))if(w.actor!=null&&w.actor.player()==p&&w.hook!=null&&w.hook.equals(event.getHookEntity().getUUID())&&w.operation!=null&&w.action.equals("FISH_REEL")){w.fishedEvent=true;w.fishedItems=event.getDrops().stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();}}
}
