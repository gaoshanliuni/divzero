package dev.mineagent.runtime.neoforge.skill;
import dev.mineagent.runtime.core.task.SkillSpec;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class MovementSkills {
    static Vec3 point(SkillSpec.Point p){return new Vec3(p.x(),p.y(),p.z());}
    static void follow(SkillWork w){var spec=w.session.spec();var target=spec.target().equals("$owner")?w.runtime.server.getPlayerList().getPlayer(w.session.owner()):w.player().level().getEntity(UUID.fromString(spec.target()));
        if(target==null||!target.isAlive()||target.level()!=w.player().level()){w.waitFor("TARGET_ABSENT_OR_OTHER_DIMENSION",20);return;}
        double distance=w.player().distanceTo(target);if(distance<=spec.stopDistance()){w.chasing=false;w.waitFor("FOLLOW_DISTANCE_SATISFIED",5);return;}if(!w.chasing&&distance<spec.startDistance()){w.waitFor("FOLLOW_HYSTERESIS",5);return;}w.chasing=true;
        if(spec.allowTeleport()&&distance>64&&w.actor instanceof AiSkillActor){var owner=w.runtime.server.getPlayerList().getPlayer(w.session.owner());if(owner!=null&&owner.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)&&new NativeTraversalEvaluator(w.player()).closest(target.position())!=null){w.player().teleportTo(w.player().level(),target.getX(),target.getY(),target.getZ(),Set.of(),w.player().getYRot(),w.player().getXRot(),true);w.session.add("explicitTeleports",1);w.runtime.persist(w);return;}}
        w.session.phase("FOLLOW_ENTITY");w.actor.sprint(w.token(),distance>spec.startDistance());w.actor.move(w.token(),target.position());
    }
    static void patrol(SkillWork w){var spec=w.session.spec();int i=w.session.waypoint();if(i>=spec.route().size())i=0;var target=point(spec.route().get(i));w.session.phase("WAYPOINT_"+i);
        if(!w.move(target))return;w.session.add("waypointsReached",1);int next=i+w.session.direction();if(next<0||next>=spec.route().size()){w.session.add("patrolCycles",1);if(!spec.repeat()||spec.limit()>0&&w.session.count("patrolCycles")>=spec.limit()){w.completed("PATROL_ROUTE_COMPLETE");return;}if(spec.pingPong()&&spec.route().size()>1){w.session.direction(-w.session.direction());next=i+w.session.direction();}else next=0;}
        w.session.waypoint(next);w.runtime.persist(w);w.waitFor("WAYPOINT_DWELL",spec.dwellTicks());
    }
    static void wander(SkillWork w){var area=w.session.spec().area();if(w.wanderTarget!=null){if(w.move(w.wanderTarget)){w.wanderTarget=null;w.session.add("wanderVisits",1);if(!w.session.spec().repeat()||w.session.spec().limit()>0&&w.session.count("wanderVisits")>=w.session.spec().limit()){w.completed("WANDER_TARGET_MET");return;}w.waitFor("IDLE_OBSERVE",w.session.spec().dwellTicks());}return;}
        var random=new Random(w.token().getLeastSignificantBits()+w.tick()*7919L);if(w.wanderSearch==null&&random.nextInt(4)==0){w.waitFor("IDLE_REST",20+random.nextInt(40));return;}
        if(w.wanderSearch==null){w.wanderEvaluator=new NativeTraversalEvaluator(w.player());var origin=w.wanderEvaluator.closest(w.player().position());if(origin==null){w.waitFor("NO_SAFE_IDLE_ORIGIN",40);return;}w.wanderSearch=new dev.mineagent.runtime.core.task.SurfaceReachability(origin,w.wanderEvaluator);}
        var budget=NativeNavigationBudget.get(w.runtime.server);int count=budget.claim(w.token(),w.tick());if(count==0)return;w.wanderEvaluator.beginSlice();w.wanderSearch.advance(Math.min(count,32),budget::timeAvailable);
        var candidates=w.wanderSearch.reached().stream().filter(n->!w.wanderEvaluator.water(n)&&!w.wanderEvaluator.climb(n)).map(NativeTraversalEvaluator::point).filter(p->p.distanceToSqr(w.player().position())>=4&&p.distanceToSqr(w.player().position())<=64&&area.contains(new SkillSpec.Point(p.x,p.y,p.z))).toList();
        if(!candidates.isEmpty()){w.wanderTarget=candidates.get(new Random(w.token().getMostSignificantBits()^w.tick()).nextInt(candidates.size()));w.wanderSearch=null;w.session.phase("WANDER");w.actor.move(w.token(),w.wanderTarget);return;}
        if(w.wanderSearch.exhausted()||w.wanderSearch.reached().size()>512){w.wanderSearch=null;w.waitFor(w.wanderEvaluator.encounteredUnloaded()?"WAITING_CHUNKS":"NO_REACHABLE_IDLE_POINT",40);}
    }
    static void guard(SkillWork w){if(w.session.spec().area()==null||w.session.spec().combat().engagement()==dev.mineagent.runtime.core.task.CombatPolicy.Engagement.PROTECT){var target=w.combat.protectedEntity;if(target==null){w.waitFor("PROTECTED_TARGET_ABSENT",10);return;}double distance=w.player().distanceTo(target);if(distance<=w.session.spec().stopDistance())w.chasing=false;else if(distance>w.session.spec().startDistance())w.chasing=true;if(w.chasing)w.actor.move(w.token(),target.position());else w.waitFor("PROTECTING_TARGET",10);}else if(w.session.spec().route().isEmpty())wander(w);else patrol(w);}
    private MovementSkills(){}
}
