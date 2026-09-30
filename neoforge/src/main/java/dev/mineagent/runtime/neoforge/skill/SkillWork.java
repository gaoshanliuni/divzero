package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.api.agent.BodyDomain;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.task.SkillSession.State;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.*;

/** Runtime-only handles are fenced by the persistent session, actor instance and intent revision. */
final class SkillWork {
    final SkillRuntime runtime;final SkillSession session;SkillActor actor;CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);long dbRevision,lastTick,nextTick;volatile boolean ioFailed;boolean starting;
    java.util.function.BooleanSupplier externalAuthority=()->true;
    BlockPos block;CropAdapter crop;Vec3 stand,aim;UUID targetEntity,hook;InteractionTargetResolver.Query search;int startedTick,beforeCount,beforeDamage,workStage,combatStage;String expectedState="",action="";UUID operation;boolean executed;
    double healthBefore;long shotsBefore;UUID fighting,combatOperation;String suspendedPhase;boolean chasing,nativeBreak,interruptedOperation;Vec3 wanderTarget;long lastSave;int combatAt,combatAmmo;
    boolean fishedEvent,tillPlot,shotLogged,nativeUse;int fishedItems,fishStatBefore,nativeConsumed,nativeDurability;Map<String,Integer> fishInventoryBefore=Map.of();Set<UUID> nearbyItemsBefore=Set.of();
    NativeTraversalEvaluator wanderEvaluator;SurfaceReachability wanderSearch;
    LivingEntity lastCombatTarget;
    final CombatFootwork footwork=new CombatFootwork();String jumpKind="COUNTER";int jumpTapUntil=-1,sideStepUntil=-1;Vec3 footworkPosition;
    final CombatAwareness combat=new CombatAwareness();final CombatPositioning positioning=new CombatPositioning();
    String tactic="OBSERVE";int tacticAt,lastAttackAt=-10000,lastHitAt=-10000,lastDefenseTick,healSlot=-1;UUID healingOperation;boolean combatInterrupted;
    int lastCombatTick=-1,foodBefore;boolean lastCombatResult,healingWasUsing;
    int contactSince=-1,lastContactDamage=-10000,contactClearSince=-1;boolean contactEscape,contactRunAndHit,sprintApproach;Vec3 contactEscapeOrigin,contactEscapeLastPosition;UUID lastMeleeHitTarget;int lastMeleeHitTick=-10000,comboStreak;
    UUID extensionOperation,shieldOperation;boolean wasBlocking;FishingTackleAdapter tackle=FishingTackleAdapter.VANILLA;
    int lastTacticalJump=-10000,observedTacticalJump=-10000;double tacticalJumpY;
    private String lastNotice="";private int lastNoticeTick=-10000;
    void notice(String key,String fallback){if(key.equals(lastNotice)&&tick()-lastNoticeTick<200)return;lastNotice=key;lastNoticeTick=tick();var owner=runtime.server.getPlayerList().getPlayer(session.owner());if(owner==null)return;String name=MineAgentRuntimeServices.bodies(runtime.server).definitions().stream().filter(d->d.agentId().equals(session.agent())).map(d->d.displayName()).findFirst().orElse("AI");owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("["+name+"] ").append(net.minecraft.network.chat.Component.translatableWithFallback("mineagent.behavior."+key,fallback)));}
    final Map<UUID,SkillLootCollector.Drop> loot=new LinkedHashMap<>();final Map<Item,Integer> lootBefore=new HashMap<>();Set<UUID> dropBefore=Set.of();final Set<UUID> pickedDrops=new HashSet<>();int lootRetries;Long blockedLootTerrain;
    SkillWork(SkillRuntime runtime,SkillSession session,long dbRevision){this.runtime=runtime;this.session=session;this.dbRevision=dbRevision;}
    ServerPlayer player(){return actor.player();}int tick(){return runtime.server.getTickCount();}UUID token(){return session.id();}
    Map<String,Object> view(){var out=new LinkedHashMap<String,Object>();out.put("session",session.snapshot());out.put("actor",actor==null?Map.of():actor.observation());out.put("currentTarget",block==null?List.of():List.of(block.getX(),block.getY(),block.getZ()));out.put("nextCheckTick",nextTick);out.put("pendingReceipt",!saved.isDone());out.put("combat",combat.view());out.put("tactic",tactic);return out;}
    void bind(ServerPlayer owner)throws Exception{release();if(session.spec().actor().equals("player"))actor=new PlayerSkillActor(owner,session.agent(),token(),session.spec().title());else{var body=MineAgentRuntimeServices.bodies(runtime.server).body(session.agent()).orElse(null);if(body==null){session.transition(State.WAITING,"BODY_UNAVAILABLE");nextTick=tick()+20;return;}actor=new AiSkillActor(body);}block=null;search=null;stand=null;operation=null;executed=false;fighting=null;session.phase("SCAN");runtime.index(this);}
    boolean acquire(){int priority=combatInterrupted||fighting!=null||session.spec().kind()==SkillSpec.Kind.COMBAT?80:session.spec().kind()==SkillSpec.Kind.WANDER?5:30;
        if(actor.controls().acquire(token(),EnumSet.allOf(BodyDomain.class),priority,()->session.runnable()&&actor.current(),()->{actor.stop(token());runtime.reservations.release(token());if(session.runnable())session.transition(State.SUSPENDED,"PREEMPTED");}))return true;
        session.transition(State.SUSPENDED,"HIGHER_PRIORITY_BEHAVIOR");return false;}
    void release(){if(actor!=null){actor.stop(token());actor.controls().release(token());}runtime.reservations.release(token());}
    void pause(String reason){release();if(!session.terminal())session.transition(State.PAUSED,reason);if(!ioFailed)runtime.persist(this);}
    void waitFor(String reason,int ticks){release();session.transition(State.WAITING,reason);nextTick=tick()+ticks;if(actor instanceof PlayerSkillActor p)p.report(reason,false);switch(reason){case "SEED_REQUIRED_FOR_REPLANT","SEED_NOT_READY"->notice("seeds","缺少补种种子，等待补充。");case "INVENTORY_FULL"->notice("inventory","背包放不下产物，等待整理。");case "FISHING_ROD_MISSING"->notice("rod","缺少可用鱼竿，等待补充。");case "LOOT_ROUTE_REQUIRES_NEW_APPROACH"->notice("loot_route","产物暂不可达，需要调整拾取路线。");}if(tick()-lastSave>100){runtime.persist(this);lastSave=tick();}}
    void completed(String reason){release();session.transition(State.COMPLETED,reason);if(actor instanceof PlayerSkillActor p)p.report(reason,true);runtime.persist(this);runtime.resumePrevious(this);}
    void step()throws Exception{
        session.transition(State.RUNNING,"");
        if(CombatSkill.interruptOrContinue(this))return;
        switch(session.spec().kind()){
            case IDLE->waitFor("IDLE_BY_PLAYER",10);case FOLLOW->MovementSkills.follow(this);case PATROL->MovementSkills.patrol(this);case WANDER->MovementSkills.wander(this);case GUARD->MovementSkills.guard(this);
            case COMBAT->CombatSkill.combat(this);case FARM->FarmSkill.tick(this);case FISH->FishSkill.tick(this);
        }
    }
    boolean at(Vec3 target){return player().position().subtract(target).horizontalDistanceSqr()<.16&&Math.abs(player().getY()-target.y)<.35;}
    boolean move(Vec3 target){if(at(target)){actor.stop(token());return true;}String state=actor.move(token(),target);if(state.equals("ARRIVED")&&player().position().subtract(target).horizontalDistanceSqr()<.16&&Math.abs(player().getY()-target.y)<1.251){actor.stop(token());return true;}if(state.equals("UNREACHABLE")||state.equals("INTERACTION_BLOCKED")){session.add("unreachable",1);waitFor(state,60);abandonTarget();wanderTarget=null;wanderSearch=null;}return false;}
    boolean reach(BlockPos target,InteractionTargetResolver.Kind kind){
        if(stand!=null)return move(stand);
        if(search==null)search=InteractionTargetResolver.block(player(),target,kind);
        if(search.search()==null){waitFor(search.evaluator().encounteredUnloaded()?"WAITING_CHUNKS":"NO_INTERACTION_POSITION",60);search=null;return false;}
        var budget=NativeNavigationBudget.get(runtime.server);int allowed=budget.claim(token(),tick());if(allowed==0)return false;search.evaluator().beginSlice();var result=search.search().advance(allowed,budget::timeAvailable);
        if(result.status()==SurfacePathfinder.Status.FOUND){stand=result.steps().isEmpty()?player().position():NativeTraversalEvaluator.point(result.steps().getLast().to());search=null;return move(stand);}
        if(result.status()!=SurfacePathfinder.Status.BUDGET_EXHAUSTED){session.add("unreachable",1);search=null;waitFor(result.status().name(),60);}return false;
    }
    int count(Item item){return player().getInventory().countItem(item);}
    boolean equip(Item item){if(player().getMainHandItem().is(item))return true;for(int i=0;i<36;i++)if(player().getInventory().getItem(i).is(item)){actor.select(token(),i);return player().getMainHandItem().is(item);}return false;}
    boolean inventorySpace(){for(int i=0;i<36;i++)if(player().getInventory().getItem(i).isEmpty())return true;return false;}
    /** Persist before issuing a side effect. Re-entry observes this operation, never allocates another. */
    boolean prepare(String action,Map<String,String> before){if(operation!=null)return saved.isDone()&&!ioFailed;operation=UUID.randomUUID();this.action=action;executed=false;nativeUse=false;nativeConsumed=nativeDurability=0;startedTick=tick();var receipt=new LinkedHashMap<>(before);receipt.put("operation",operation.toString());receipt.put("state","PREPARED");receipt.put("action",action);receipt.put("actorEntityId",Integer.toString(player().getId()));receipt.put("intentRevision",Long.toString(session.revision()));receipt.put("dimension",session.spec().dimension());session.receipt(receipt);runtime.persist(this);return false;}
    void confirm(String result,Map<String,String> observed){var receipt=new LinkedHashMap<>(session.receipt());receipt.put("state",result);receipt.putAll(observed);session.receipt(receipt);runtime.persist(this);operation=null;executed=false;interruptedOperation=false;actor.stop(token());}
    String reservation(BlockPos p){return session.spec().dimension()+"/"+p.asLong();}
    void abandonTarget(){if(block!=null)runtime.reservations.release(reservation(block),token());block=null;stand=null;search=null;crop=null;tillPlot=false;workStage=0;}
}
