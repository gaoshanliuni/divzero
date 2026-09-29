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
        w.session.phase("FOLLOW_ENTITY");w.actor.move(w.token(),target.position());
    }
    static void patrol(SkillWork w){var spec=w.session.spec();int i=w.session.waypoint();if(i>=spec.route().size())i=0;var target=point(spec.route().get(i));w.session.phase("WAYPOINT_"+i);
        if(!w.move(target))return;w.session.add("waypointsReached",1);int next=i+w.session.direction();if(next<0||next>=spec.route().size()){w.session.add("patrolCycles",1);if(!spec.repeat()||spec.limit()>0&&w.session.count("patrolCycles")>=spec.limit()){w.completed("PATROL_ROUTE_COMPLETE");return;}if(spec.pingPong()&&spec.route().size()>1){w.session.direction(-w.session.direction());next=i+w.session.direction();}else next=0;}
        w.session.waypoint(next);w.runtime.persist(w);w.waitFor("WAYPOINT_DWELL",spec.dwellTicks());
    }
    static void wander(SkillWork w){var area=w.session.spec().area();if(w.wanderTarget!=null){if(w.move(w.wanderTarget)){w.wanderTarget=null;w.session.add("wanderVisits",1);w.waitFor("IDLE_OBSERVE",w.session.spec().dwellTicks());}return;}
        if(w.tick()%4==0){w.waitFor("IDLE_REST",20);return;}
        var random=new Random(w.token().getMostSignificantBits()^w.tick());var eval=new NativeTraversalEvaluator(w.player());
        for(int attempt=0;attempt<12&&w.runtime.scan();attempt++){int x=(int)Math.floor(w.player().getX())+random.nextInt(17)-8,z=(int)Math.floor(w.player().getZ())+random.nextInt(17)-8;var n=eval.closest(new Vec3(x+.5,w.player().getY(),z+.5));if(n==null)continue;var p=NativeTraversalEvaluator.point(n);if(!area.contains(new SkillSpec.Point(p.x,p.y,p.z)))continue;w.wanderTarget=p;w.session.phase("WANDER");w.actor.move(w.token(),p);return;}w.waitFor(eval.encounteredUnloaded()?"WAITING_CHUNKS":"NO_REACHABLE_IDLE_POINT",40);
    }
    static void guard(SkillWork w){if(w.session.spec().route().isEmpty())wander(w);else patrol(w);}
    private MovementSkills(){}
}
