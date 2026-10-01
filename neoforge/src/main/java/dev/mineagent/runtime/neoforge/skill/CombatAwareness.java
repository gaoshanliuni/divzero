package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.CombatPolicy;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Every nearby relevant entity contributes to risk. Selection is sticky, not nearest-only. */
final class CombatAwareness {
    record Threat(LivingEntity entity,NativeCombatStates.Snapshot state,boolean eligible,boolean protecting,boolean urgent,double score){}
    List<Threat> threats=List.of();List<Projectile> projectiles=List.of();Vec3 anchor;LivingEntity protectedEntity;int lastThreatTick=-10000,nextScan;long scans;
    private record Seen(LivingEntity entity,Vec3 position,int tick){}
    private final Map<UUID,Seen> lastSeen=new LinkedHashMap<>();private UUID searching;private int searchStarted;
    private record Deferred(Vec3 actor,Vec3 target,long terrain,int retryAt){}
    private final Map<UUID,Deferred> deferred=new HashMap<>();
    void clearSearch(){lastSeen.clear();searching=null;deferred.clear();}
    private int terrainTick=-1;private net.minecraft.core.BlockPos terrainAt;private long terrainValue;
    boolean hasDeferred(SkillWork w){return deferred.values().stream().anyMatch(v->v.retryAt>w.tick());}
    private long terrain(SkillWork w){var origin=w.player().blockPosition();if(terrainTick==w.tick()&&origin.equals(terrainAt))return terrainValue;long hash=1;for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=2;y++){var at=origin.offset(x,y,z);hash=hash*31+(w.player().level().hasChunkAt(at)?net.minecraft.world.level.block.Block.getId(w.player().level().getBlockState(at)):-1);}terrainTick=w.tick();terrainAt=origin;return terrainValue=hash;}
    boolean deferred(SkillWork w,LivingEntity entity){var entry=deferred.get(entity.getUUID());if(entry==null)return false;
        boolean hit=w.player().getLastHurtByMob()==entity&&w.player().tickCount-w.player().getLastHurtByMobTimestamp()<20;
        if(!entity.isAlive()||entity.level()!=w.player().level()||w.tick()>=entry.retryAt||entity.position().distanceToSqr(entry.target)>4||w.player().position().distanceToSqr(entry.actor)>4||hit||sight(w,entity)||terrain(w)!=entry.terrain){deferred.remove(entity.getUUID());return false;}return true;
    }
    void unreachable(SkillWork w,LivingEntity entity){deferred.put(entity.getUUID(),new Deferred(w.player().position(),entity.position(),terrain(w),w.tick()+100));if(selected==entity)selected=null;lastSeen.remove(entity.getUUID());searching=null;nextScan=0;lastThreatTick=w.tick()-40;w.session.add("unreachableThreatDeferrals",1);}
    Vec3 lastSeenSearch(SkillWork w){
        var policy=w.session.spec().combat();if(policy.engagement()!=CombatPolicy.Engagement.CLEAR_AREA||policy.area()==null)return null;
        lastSeen.entrySet().removeIf(entry->{var seen=entry.getValue();return !seen.entity.isAlive()||seen.entity.level()!=w.player().level()||w.tick()-seen.tick>2400||policy.excluded().contains(entry.getKey())||!policy.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(seen.position.x,seen.position.y,seen.position.z));});
        if(searching!=null&&w.tick()-searchStarted>500){lastSeen.remove(searching);searching=null;}
        lastSeen.entrySet().removeIf(entry->entry.getValue().position.distanceToSqr(w.player().position())<9&&w.tick()-entry.getValue().tick>10);
        Seen selected=lastSeen.values().stream().min(Comparator.comparingDouble(seen->seen.position.distanceToSqr(w.player().position()))).orElse(null);
        if(selected==null){searching=null;return null;}
        if(!selected.entity.getUUID().equals(searching)){searching=selected.entity.getUUID();searchStarted=w.tick();w.session.add("lastSeenThreatSearches",1);}
        return selected.position;
    }
    void searchFailed(){if(searching!=null)lastSeen.remove(searching);searching=null;}
    private int geometryTick=-1;private Boolean cachedFlanked;private final Map<UUID,Boolean> sightCache=new HashMap<>();private final Map<UUID,Boolean> reverseSightCache=new HashMap<>();
    private void geometry(SkillWork w){if(geometryTick!=w.tick()){geometryTick=w.tick();cachedFlanked=null;sightCache.clear();reverseSightCache.clear();}}
    private boolean sight(SkillWork w,LivingEntity entity){geometry(w);return sightCache.computeIfAbsent(entity.getUUID(),id->dev.mineagent.runtime.neoforge.body.NativeTargetGeometry.canObserve(w.player(),entity));}
    private boolean reverseSight(SkillWork w,LivingEntity entity){geometry(w);return reverseSightCache.computeIfAbsent(entity.getUUID(),id->entity.hasLineOfSight(w.player()));}
    LivingEntity selected;int selectedAt;double lastDamageVelocity;
    final NativeAttackTimeline attacks=new NativeAttackTimeline();
    void scan(SkillWork w){
        attacks.observe(w);
        var p=w.player();var rule=w.session.spec().combat();if(anchor==null||!w.combatInterrupted)anchor=p.position();if(rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(p.getX(),p.getY(),p.getZ())))anchor=new Vec3((rule.area().min().x()+rule.area().max().x()+1)/2,p.getY(),(rule.area().min().z()+rule.area().max().z()+1)/2);
        protectedEntity=rule.protect().isBlank()?null:rule.protect().equals("$owner")?w.runtime.server.getPlayerList().getPlayer(w.session.owner()):p.level().getEntity(UUID.fromString(rule.protect())) instanceof LivingEntity e?e:null;
        if(protectedEntity!=null&&protectedEntity.level()!=p.level())protectedEntity=null;
        // Released attacks move every tick; their observation does not wait for the broader target-selection scan.
        projectiles=List.copyOf(p.level().getEntitiesOfClass(Projectile.class,p.getBoundingBox().inflate(rule.awareness()),e->e.isAlive()&&e.getOwner()!=p&&!(e.getOwner()!=null&&e.getOwner().isAlliedTo(p))&&e.getDeltaMovement().lengthSqr()>.001&&!(e instanceof dev.mineagent.runtime.neoforge.mixin.CombatArrowStateAccess arrow&&arrow.divzero$inGround())));
        if(w.tick()<nextScan)return;nextScan=w.tick()+4+Math.floorMod(w.token().hashCode(),3);scans++;
        var owner=w.runtime.creator(w);
        boolean nearbyCreator=owner!=null&&owner!=p&&owner.isAlive()&&owner.level()==p.level()&&owner.distanceTo(p)<=rule.awareness()*2;
        var rows=new ArrayList<Threat>();var bounds=p.getBoundingBox();if(nearbyCreator&&rule.assistCreator())bounds=bounds.minmax(owner.getBoundingBox());if(protectedEntity!=null&&protectedEntity.distanceTo(p)<rule.awareness()*2)bounds=bounds.minmax(protectedEntity.getBoundingBox());var entities=p.level().getEntitiesOfClass(LivingEntity.class,bounds.inflate(rule.awareness()),e->e!=p&&e.isAlive());
        w.prediction.observe(w,entities);
        var hostilePositions=new dev.mineagent.runtime.core.task.SpatialNeighbors<LivingEntity>(entities.stream().filter(e->e instanceof Enemy||e instanceof net.minecraft.world.entity.player.Player&&SkillRuntime.attackAllowed(w,e)).toList(),4,e->new dev.mineagent.runtime.core.task.SpatialNeighbors.Point(e.getX(),e.getY(),e.getZ()));
        var dependents=new HashMap<UUID,Integer>();
        for(var entity:entities)if(entity instanceof net.minecraft.world.entity.monster.Vex vex&&vex.getOwner()!=null&&sight(w,vex))dependents.merge(vex.getOwner().getUUID(),1,Integer::sum);
        for(var e:entities){
            boolean forbidden=!SkillRuntime.attackAllowed(w,e);
            boolean helpOwner=nearbyCreator&&e.distanceTo(owner)<=rule.leash()&&rule.assists(w.session.spec().kind(),
                    w.runtime.creatorAttacked(owner,e),owner.getLastHurtByMob()==e&&owner.tickCount-owner.getLastHurtByMobTimestamp()<100,
                    e instanceof Mob mob&&!mob.isNoAi()&&mob.getTarget()==owner);
            boolean self=e instanceof Mob mob&&mob.getTarget()==p;
            boolean protect=helpOwner||protectedEntity!=null&&e instanceof Mob mob&&mob.getTarget()==protectedEntity;
            boolean attacked=p.getLastHurtByMob()==e&&p.tickCount-p.getLastHurtByMobTimestamp()<100;
            if(forbidden&&!self&&!protect&&!attacked)continue;
            boolean specified=e.getUUID().toString().equals(rule.target())||IsolatedCombatArena.opponents(p,e);
            if(!(e instanceof Enemy)&&!self&&!protect&&!attacked&&!specified)continue;
            double d=e.distanceTo(p);boolean sight=sight(w,e);
            boolean approaching=e.getDeltaMovement().dot(p.position().subtract(e.position()))>0;
            boolean imminent=sight&&e instanceof Enemy&&e instanceof Mob nativeMob&&!nativeMob.isNoAi()&&(approaching&&d<6||nativeMob.getTarget()==null&&d<3);
            boolean eligible=switch(rule.engagement()){
                case NONE->false;case SELF_DEFENSE->self||attacked||imminent||helpOwner;
                case PROTECT->self||attacked||imminent||protect||protectedEntity!=null&&e instanceof Enemy&&e.distanceTo(protectedEntity)<6&&e.hasLineOfSight(protectedEntity);
                case CLEAR_AREA->self||attacked||imminent||helpOwner||rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(e.getX(),e.getY(),e.getZ()));
                case SPECIFIED->specified||helpOwner;
            };
            Vec3 center=protectedEntity!=null?protectedEntity.position():anchor;
            boolean assigned=rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(e.getX(),e.getY(),e.getZ()));
            if(forbidden||e.position().distanceTo(center)>rule.leash()&&!assigned&&!(self||protect||attacked||imminent))eligible=false;
            if(deferred(w,e))eligible=false;
            var actual=NativeCombatStates.read(e,p);
            long neighbors=hostilePositions.count(new dev.mineagent.runtime.core.task.SpatialNeighbors.Point(e.getX(),e.getY(),e.getZ()),4,e);
            double score=(self?8:0)+(protect?18+(NativeCombatStates.meleeAt(e,helpOwner?owner:protectedEntity,(helpOwner?owner:protectedEntity).position())?30:0):0)+(attacked?6:0)+(imminent?5:0)+Math.max(0,8-d)*.7-neighbors*2-(actual.areaAttack()?6:0)+(selected==e?3:0)+(sight?1:-4);
            boolean summoning=actual.attacks().stream().anyMatch(a->a.kind().equals("SUMMON")&&a.running());
            int nextCast=actual.attacks().stream().filter(a->a.kind().equals("SUMMON")).mapToInt(NativeCombatStates.Attack::cooldownTicks).min().orElse(Integer.MAX_VALUE);
            if(sight)score+=dev.mineagent.runtime.core.task.CombatSourcePriority.bonus(d,dependents.getOrDefault(e.getUUID(),0),summoning,nextCast);
            // A distant producer must not prevent a ready native hit on an already reachable attacker.
            if(sight&&p.getAttackStrengthScale(.5f)>=.95f&&p.isWithinAttackRange(p.getMainHandItem(),e.getHitbox(),0))score+=16;
            rows.add(new Threat(e,actual,eligible,protect,self||protect||attacked||imminent,score));
            if(eligible&&rule.engagement()==CombatPolicy.Engagement.CLEAR_AREA){var previous=lastSeen.put(e.getUUID(),new Seen(e,e.position(),w.tick()));if(previous!=null&&w.tick()-previous.tick>20)w.session.add("threatReacquisitions",1);}
        }
        threats=List.copyOf(rows);
        deferred.keySet().removeIf(id->p.level().getEntity(id)==null);
        if(selected!=null&&deferred(w,selected))selected=null;
        if(rows.stream().anyMatch(t->(t.eligible||t.urgent)&&!deferred(w,t.entity))||attacks.standingRisk(w,p.position(),8)>0)lastThreatTick=w.tick();
        var best=rows.stream().filter(Threat::eligible).max(Comparator.comparingDouble(Threat::score)).orElse(null);
        if(best!=null){if(selected!=best.entity){selected=best.entity;selectedAt=w.tick();w.session.add("targetChanges",1);}}else selected=null;
    }
    Vec3 center(SkillWork w){return protectedEntity!=null?protectedEntity.position():anchor==null?w.player().position():anchor;}
    int contacts(SkillWork w){return (int)threats.stream().filter(t->t.entity.isAlive()&&NativeCombatStates.meleeAt(t.entity,w.player(),w.player().position())&&NativeCombatStates.meleeVisibleAt(t.entity,w.player(),w.player().position(),Vec3.ZERO)).count();}
    boolean flanked(SkillWork w){geometry(w);if(cachedFlanked!=null)return cachedFlanked;var p=w.player();var angles=new ArrayList<Double>();
        for(var threat:threats){var e=threat.entity;if(e.isAlive()&&NativeCombatStates.contactCapable(e)&&!deferred(w,e)&&e.distanceToSqr(p)<36&&Math.abs(e.getY()-p.getY())<3&&NativeCombatStates.meleeVisibleAt(e,p,p.position(),Vec3.ZERO)){var delta=e.position().subtract(p.position());if(delta.horizontalDistanceSqr()>.001)angles.add(Math.atan2(delta.z,delta.x));}}
        return cachedFlanked=dev.mineagent.runtime.core.task.SpatialNeighbors.opposing(angles,Math.acos(-.2));
    }
    double risk(SkillWork w,Vec3 point){return risk(w,point,null);}
    double risk(SkillWork w,Vec3 point,LivingEntity attackOpportunity){
        double risk=0;for(var threat:threats){var e=threat.entity;if(!e.isAlive())continue;double d=e.position().distanceTo(point),future=e.position().add(e.getDeltaMovement().scale(5)).distanceTo(point);risk+=Math.max(0,5-Math.min(d,future))*2;
            if(NativeCombatStates.meleeAt(e,w.player(),point)&&NativeCombatStates.meleeVisibleAt(e,w.player(),point,Vec3.ZERO))risk+=threat.state.openingTicks(w.player().level().getGameTime())>=6?2:threat.state.meleeRestricted()?3:e==attackOpportunity?4:18;
            double areaRange=threat.state.attacks().stream().filter(a->a.kind().equals("AREA")&&a.running()).mapToDouble(NativeCombatStates.Attack::maxRange).max().orElse(0);
            if(areaRange>0&&!(e instanceof net.minecraft.world.entity.monster.Ravager)&&!(e instanceof net.minecraft.world.entity.monster.Creeper)&&d<areaRange+1)risk+=30+Math.max(0,areaRange-d)*5;if(threat.state.ranged()&&reverseSight(w,e))risk+=Math.max(0,8-d);
        }
        risk+=attacks.standingRisk(w,point,8);
        return risk;
    }
    double collisionRisk(SkillWork w,Vec3 point,int ticks){return collisionRisk(w,point,ticks,null);}
    double collisionRisk(SkillWork w,Vec3 point,int ticks,LivingEntity attackOpportunity){
        double risk=0;
        if(w.legacyBaseline()){for(var threat:threats)if(threat.entity.isAlive()){
            if(NativeCombatStates.meleeAtAfter(threat.entity,w.player(),point,ticks))risk+=threat.state.openingTicks(w.player().level().getGameTime())>ticks+2?2:threat.entity==attackOpportunity?4:18;
            if(threat.state.areaAttack()&&threat.entity.position().distanceTo(point)<8)risk+=30;
        }}else risk=w.prediction.risk(w,point,ticks,attackOpportunity);
        return risk+attacks.risk(w,point,point,Math.max(1,ticks));
    }
    boolean incoming(SkillWork w){return attacks.standingRisk(w,w.player().position(),8)>0;}
    Map<String,Object> view(){return Map.of("targetName",selected==null?"":selected.getName().getString(),"observations",scans,"target",selected==null?"":selected.getUUID().toString(),"threats",threats.stream().map(t->Map.of("actual",t.state,"eligible",t.eligible,"urgent",t.urgent,"selectionScore",t.score)).toList(),"projectileThreats",projectiles.size(),"lastThreatTick",lastThreatTick,"attackTimeline",attacks.view(),"unreachableTargets",deferred.keySet().stream().map(UUID::toString).toList());}
}
