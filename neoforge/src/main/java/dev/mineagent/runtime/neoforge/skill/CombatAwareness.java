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
    void clearSearch(){lastSeen.clear();searching=null;}
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
    LivingEntity selected;int selectedAt;double lastDamageVelocity;
    void scan(SkillWork w){
        var p=w.player();var rule=w.session.spec().combat();if(anchor==null||!w.combatInterrupted)anchor=p.position();if(rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(p.getX(),p.getY(),p.getZ())))anchor=new Vec3((rule.area().min().x()+rule.area().max().x()+1)/2,p.getY(),(rule.area().min().z()+rule.area().max().z()+1)/2);
        protectedEntity=rule.protect().isBlank()?null:rule.protect().equals("$owner")?w.runtime.server.getPlayerList().getPlayer(w.session.owner()):p.level().getEntity(UUID.fromString(rule.protect())) instanceof LivingEntity e?e:null;
        if(protectedEntity!=null&&protectedEntity.level()!=p.level())protectedEntity=null;
        if(w.tick()<nextScan)return;nextScan=w.tick()+4+Math.floorMod(w.token().hashCode(),3);scans++;
        var rows=new ArrayList<Threat>();var bounds=p.getBoundingBox();if(protectedEntity!=null&&protectedEntity.distanceTo(p)<rule.awareness()*2)bounds=bounds.minmax(protectedEntity.getBoundingBox());var entities=p.level().getEntitiesOfClass(LivingEntity.class,bounds.inflate(rule.awareness()),e->e!=p&&e.isAlive());
        for(var e:entities){
            boolean forbidden=e instanceof net.minecraft.world.entity.player.Player||e.isAlliedTo(p)||e instanceof OwnableEntity own&&own.getOwnerReference()!=null||rule.excluded().contains(e.getUUID());
            boolean self=e instanceof Mob mob&&mob.getTarget()==p;
            boolean protect=protectedEntity!=null&&e instanceof Mob mob&&mob.getTarget()==protectedEntity;
            boolean attacked=p.getLastHurtByMob()==e&&p.tickCount-p.getLastHurtByMobTimestamp()<100;
            if(forbidden&&!self&&!protect&&!attacked)continue;
            boolean specified=e.getUUID().toString().equals(rule.target());
            if(!(e instanceof Enemy)&&!self&&!protect&&!attacked&&!specified)continue;
            double d=e.distanceTo(p);boolean sight=p.hasLineOfSight(e);
            boolean approaching=e.getDeltaMovement().dot(p.position().subtract(e.position()))>0;
            boolean imminent=sight&&e instanceof Enemy&&e instanceof Mob nativeMob&&!nativeMob.isNoAi()&&(approaching&&d<6||nativeMob.getTarget()==null&&d<3);
            boolean eligible=switch(rule.engagement()){
                case NONE->false;case SELF_DEFENSE->self||attacked||imminent;
                case PROTECT->self||attacked||imminent||protect||protectedEntity!=null&&e instanceof Enemy&&e.distanceTo(protectedEntity)<6&&e.hasLineOfSight(protectedEntity);
                case CLEAR_AREA->self||attacked||imminent||rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(e.getX(),e.getY(),e.getZ()));
                case SPECIFIED->specified;
            };
            Vec3 center=protectedEntity!=null?protectedEntity.position():anchor;
            boolean assigned=rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(e.getX(),e.getY(),e.getZ()));
            if(forbidden||e.position().distanceTo(center)>rule.leash()&&!assigned&&!(self||protect||attacked||imminent))eligible=false;
            var actual=NativeCombatStates.read(e,p);
            long neighbors=entities.stream().filter(other->other!=e&&other instanceof Enemy&&other.distanceToSqr(e)<16).count();
            double score=(self?8:0)+(protect?18+(NativeCombatStates.meleeAt(e,protectedEntity,protectedEntity.position())?30:0):0)+(attacked?6:0)+(imminent?5:0)+Math.max(0,8-d)*.7-neighbors*2-(actual.areaAttack()?6:0)+(selected==e?3:0)+(sight?1:-4);
            rows.add(new Threat(e,actual,eligible,protect,self||protect||attacked||imminent,score));
            if(eligible&&rule.engagement()==CombatPolicy.Engagement.CLEAR_AREA){var previous=lastSeen.put(e.getUUID(),new Seen(e,e.position(),w.tick()));if(previous!=null&&w.tick()-previous.tick>20)w.session.add("threatReacquisitions",1);}
        }
        threats=List.copyOf(rows);
        projectiles=List.copyOf(p.level().getEntitiesOfClass(Projectile.class,p.getBoundingBox().inflate(rule.awareness()),e->e.isAlive()&&e.getOwner()!=p&&!(e.getOwner()!=null&&e.getOwner().isAlliedTo(p))&&e.getDeltaMovement().lengthSqr()>.001&&!(e instanceof dev.mineagent.runtime.neoforge.mixin.CombatArrowStateAccess arrow&&arrow.divzero$inGround())));
        if(rows.stream().anyMatch(t->t.eligible||t.urgent)||projectiles.stream().anyMatch(s->projectileRisk(s,p.position())>1))lastThreatTick=w.tick();
        var best=rows.stream().filter(Threat::eligible).max(Comparator.comparingDouble(Threat::score)).orElse(null);
        if(best!=null){if(selected!=best.entity){selected=best.entity;selectedAt=w.tick();w.session.add("targetChanges",1);}}else if(selected==null||!selected.isAlive()||selected.position().distanceTo(center(w))>rule.leash()||w.tick()-lastThreatTick>40)selected=null;
    }
    Vec3 center(SkillWork w){return protectedEntity!=null?protectedEntity.position():anchor==null?w.player().position():anchor;}
    int contacts(SkillWork w){return (int)threats.stream().filter(t->t.entity.isAlive()&&NativeCombatStates.meleeAt(t.entity,w.player(),w.player().position())).count();}
    boolean flanked(SkillWork w){var p=w.player().position();for(var a:threats)if(a.entity.distanceTo(w.player())<6)for(var b:threats)if(a!=b&&b.entity.distanceTo(w.player())<6&&a.entity.position().subtract(p).normalize().dot(b.entity.position().subtract(p).normalize())<-.2)return true;return false;}
    double risk(SkillWork w,Vec3 point){
        double risk=0;for(var threat:threats){var e=threat.entity;if(!e.isAlive())continue;double d=e.position().distanceTo(point),future=e.position().add(e.getDeltaMovement().scale(5)).distanceTo(point);risk+=Math.max(0,5-Math.min(d,future))*2;
            if(NativeCombatStates.meleeAt(e,w.player(),point))risk+=threat.state.openingTicks(w.player().level().getGameTime())>=6?2:threat.state.meleeRestricted()?3:18;
            double areaRange=threat.state.attacks().stream().filter(a->a.kind().equals("AREA")&&a.running()).mapToDouble(NativeCombatStates.Attack::maxRange).max().orElse(0);
            if(areaRange>0&&d<areaRange+1)risk+=30+Math.max(0,areaRange-d)*5;if(threat.state.ranged()&&e.hasLineOfSight(w.player()))risk+=Math.max(0,8-d);
        }
        for(var shot:projectiles)risk+=projectileRisk(shot,point)*20;
        return risk;
    }
    double collisionRisk(SkillWork w,Vec3 point,int ticks){
        double risk=0;for(var threat:threats)if(threat.entity.isAlive()){
            if(NativeCombatStates.meleeAtAfter(threat.entity,w.player(),point,ticks))risk+=threat.state.openingTicks(w.player().level().getGameTime())>ticks+2?2:18;
            if(threat.state.areaAttack()&&threat.entity.position().distanceTo(point)<8)risk+=30;
        }
        for(var shot:projectiles)risk+=projectileRisk(shot,point)*20;return risk;
    }
    private double projectileRisk(Projectile shot,Vec3 point){var v=shot.getDeltaMovement();var delta=point.add(0,1,0).subtract(shot.position());double t=Math.max(0,Math.min(12,delta.dot(v)/Math.max(.0001,v.lengthSqr())));double miss=shot.position().add(v.scale(t)).distanceTo(point.add(0,1,0));return Math.max(0,2-miss);}
    boolean incoming(SkillWork w){return projectiles.stream().anyMatch(shot->projectileRisk(shot,w.player().position())>.6);}
    Map<String,Object> view(){return Map.of("targetName",selected==null?"":selected.getName().getString(),"observations",scans,"target",selected==null?"":selected.getUUID().toString(),"threats",threats.stream().map(t->Map.of("actual",t.state,"eligible",t.eligible,"urgent",t.urgent,"selectionScore",t.score)).toList(),"projectileThreats",projectiles.size(),"lastThreatTick",lastThreatTick);}
}
