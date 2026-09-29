package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded multi-exit exploration. Score whole routes and onward space, not only endpoints. */
final class CombatPositioning {
    record Route(Node node,List<PathStep> steps,double worstRisk){}
    private NativeTraversalEvaluator evaluator;private final Deque<Route> open=new ArrayDeque<>();private final Set<Node> seen=new HashSet<>();private final List<Route> candidates=new ArrayList<>();
    private Vec3 origin;private int started;private String purpose="";private Vec3 selected;private double selectedDistance;private int candidateCursor;
    private Vec3 waypoint,heading,targetAtWaypoint;private Node waypointNode;private int waypointAt;private double waypointRisk;private String waypointPurpose="";
    private List<PathStep> chosenRoute=List.of();private final Map<Node,Integer> exposure=new HashMap<>();
    List<PathStep> route(){return chosenRoute;}
    boolean pending(){return !open.isEmpty();}
    boolean longRetreat(){return selectedDistance>3;}
    Vec3 choose(SkillWork w,String intent,double desiredDistance){
        exposure.clear();boolean withdrawal=Set.of("RETREAT","RECOVER","LURE","SPACE").contains(intent);
        var actor=w.player();boolean jumping=w.tick()-w.lastCounterJump<=20&&!actor.onGround()&&actor.getY()-w.counterJumpY>=0&&actor.getY()-w.counterJumpY<1.6;var feet=jumping?new Vec3(actor.getX(),w.counterJumpY,actor.getZ()):actor.position();
        if(waypoint!=null){
            var p=w.player();boolean reached=(p.position().subtract(waypoint).horizontalDistanceSqr()<.10||heading!=null&&p.position().subtract(waypoint).dot(heading)>.12)&&Math.abs(feet.y-waypoint.y)<.5;
            boolean targetMoved=w.combat.selected!=null&&targetAtWaypoint!=null&&w.combat.selected.position().distanceToSqr(targetAtWaypoint)>4;
            evaluator.beginSlice();var current=evaluator.closest(feet);
            boolean traversable=current!=null&&(current.equals(waypointNode)||evaluator.neighbors(current).stream().anyMatch(edge->edge.to().equals(waypointNode)));
            boolean safe=traversable&&edgeExposure(w,waypointNode)==0&&w.combat.risk(w,waypoint)<=Math.max(waypointRisk+2,w.combat.risk(w,p.position())+1);
            if(!reached&&intent.equals(waypointPurpose)&&!targetMoved&&w.tick()-waypointAt<24&&safe)return waypoint;
            if(!reached)w.session.add("tacticalWaypointRechecks",1);
            waypoint=null;origin=null;chosenRoute=List.of();
        }
        if(origin==null||w.tick()-started>8||origin.distanceToSqr(w.player().position())>1||!purpose.equals(intent)){
            evaluator=new NativeTraversalEvaluator(w.player());origin=feet;started=w.tick();purpose=intent;selected=null;open.clear();seen.clear();candidates.clear();var node=evaluator.closest(origin);if(node!=null){seen.add(node);open.add(new Route(node,List.of(),w.combat.risk(w,origin)));}
        }
        var budget=NativeNavigationBudget.get(w.runtime.server);int allowed=budget.claim(w.token(),w.tick());evaluator.beginSlice();
        for(int i=0;i<allowed&&!open.isEmpty()&&budget.timeAvailable();i++){
            var route=open.removeFirst();var point=NativeTraversalEvaluator.point(route.node);if(route.steps.size()>=1&&evaluator.neighbors(route.node).stream().filter(edge->!route.steps.stream().anyMatch(step->step.from().equals(edge.to()))).count()>=1)candidates.add(route);
            if(route.steps.size()>=7)continue;
            for(var edge:evaluator.neighbors(route.node)){
                var next=NativeTraversalEvaluator.point(edge.to());if(Math.abs(next.y-origin.y)>1.25||next.distanceToSqr(origin)>49||!seen.add(edge.to()))continue;
                if(edge.action()==Action.DROP&&(edge.from().y()-edge.to().y()>1.25||evaluator.transition(edge.to(),edge.from())==null))continue;
                if(!withdrawal&&next.distanceTo(w.combat.center(w))>w.session.spec().combat().leash())continue;
                var steps=new ArrayList<>(route.steps);steps.add(edge);open.add(new Route(edge.to(),List.copyOf(steps),Math.max(route.worstRisk,w.combat.risk(w,next))));
            }
        }
        double best=Double.POSITIVE_INFINITY;var target=w.combat.selected;
        for(int index=0;index<candidates.size()&&budget.timeAvailable();index++){var route=candidates.get(Math.floorMod(candidateCursor++,candidates.size()));var point=NativeTraversalEvaluator.point(route.node);double risk=w.combat.risk(w,point);double routeRisk=route.steps.stream().mapToDouble(step->w.combat.risk(w,NativeTraversalEvaluator.point(step.to()))).max().orElse(risk);double score=risk*2+routeRisk*.6+route.steps.size()*.15;
            // Avoid being knocked off an edge, even when a little melee damage is the cheaper exit.
            score+=route.steps.stream().mapToInt(step->edgeExposure(w,step.to())).max().orElse(0)*10000;
            double damage=0;for(var threat:w.combat.threats)if(threat.entity().isAlive()&&route.steps.stream().anyMatch(step->NativeCombatStates.meleeAt(threat.entity(),w.player(),NativeTraversalEvaluator.point(step.to())))){var attribute=threat.entity().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);damage+=attribute==null?4:Math.max(0,attribute.getValue());}
            double health=Math.max(1,w.player().getHealth()+w.player().getAbsorptionAmount());score+=damage/health*25;if(damage>=health)score+=1000;
            if(edgeExposure(w,route.node)>0)continue;
            var onwards=evaluator.neighbors(route.node).stream().filter(edge->!route.steps.stream().anyMatch(step->step.from().equals(edge.to()))).toList();
            score+=Math.max(0,3-onwards.size())*2;
            if(onwards.stream().noneMatch(edge->evaluator.neighbors(edge.to()).stream().anyMatch(next->!next.to().equals(route.node)&&route.steps.stream().noneMatch(step->step.from().equals(next.to())))))score+=100;
            if(target!=null)score+=Math.abs(point.distanceTo(target.position())-desiredDistance)*(intent.equals("COUNTER")?14:intent.equals("SPACE")||intent.equals("APPROACH")?8:1.1);
            if(target!=null&&intent.equals("COUNTER")&&point.distanceTo(target.position())>=origin.distanceTo(target.position())-.2)continue;
            if(intent.equals("RETREAT")||intent.equals("RECOVER")||intent.equals("LURE")){
                score-=origin.distanceTo(point)*.45;
                if(w.combat.protectedEntity!=null&&point.distanceTo(w.combat.protectedEntity.position())<origin.distanceTo(w.combat.protectedEntity.position())-.5)score+=100;
                var owner=w.runtime.server.getPlayerList().getPlayer(w.session.owner());if(owner!=null&&owner!=w.player()&&owner.level()==w.player().level()&&point.distanceTo(owner.position())<origin.distanceTo(owner.position())-.5)score+=100;
                var area=w.session.spec().area();if(w.session.spec().kind()==dev.mineagent.runtime.core.task.SkillSpec.Kind.GUARD&&area!=null){var center=new Vec3((area.min().x()+area.max().x())/2,(area.min().y()+area.max().y())/2,(area.min().z()+area.max().z())/2);if(point.distanceToSqr(center)<origin.distanceToSqr(center)-1)score+=30;}
            }
            if(target!=null&&intent.equals("LURE")){var a=origin.subtract(target.position()).normalize();var b=point.subtract(target.position()).normalize();score-=Math.abs(a.x*b.z-a.z*b.x)*2;}
            // Execute the first checked edge; do not hand an endpoint to a different route search.
            var next=NativeTraversalEvaluator.point(route.steps.getFirst().to());var direction=new Vec3(next.x-origin.x,0,next.z-origin.z).normalize();
            if(heading!=null)score+=(1-heading.dot(direction))*.8;
            if(score<best){best=score;selectedDistance=origin.distanceTo(point);selected=next;waypointNode=route.steps.getFirst().to();chosenRoute=route.steps;}
        }
        if(selected!=null){waypoint=selected;waypointAt=w.tick();waypointPurpose=intent;waypointRisk=w.combat.risk(w,waypoint);targetAtWaypoint=target==null?null:target.position();heading=new Vec3(waypoint.x-w.player().getX(),0,waypoint.z-w.player().getZ()).normalize();w.session.add("tacticalWaypoints",1);}
        return selected;
    }
    private int edgeExposure(SkillWork w,Node node){return exposure.computeIfAbsent(node,n->{int danger=0;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){if(dx==0&&dz==0)continue;int x=n.x()+dx,z=n.z()+dz;var at=new Vec3(x+.5,n.y(),z+.5);var pos=net.minecraft.core.BlockPos.containing(at);if(!evaluator.loaded(pos)){danger++;continue;}if(w.player().level().getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)){danger++;continue;}if(!evaluator.clear(at,net.minecraft.world.entity.Pose.STANDING,true)&&!evaluator.clear(at,net.minecraft.world.entity.Pose.CROUCHING,true))continue;if(evaluator.positions(x,z,n.y()).stream().noneMatch(floor->Math.abs(floor.y()-n.y())<=1.25))danger++;}return danger;});}
    boolean jumpSafe(SkillWork w){
        var p=w.player();boolean supported=p.onGround()||p.getDeltaMovement().y<=.01&&p.level().noCollision(p,p.getBoundingBox())&&!p.level().noCollision(p,p.getBoundingBox().move(0,-.04,0));if(!supported||p.isInWater()||p.isCrouching()||chosenRoute.size()<3)return false;
        var direction=NativeTraversalEvaluator.point(chosenRoute.get(2).to()).subtract(p.position());
        if(Math.abs(direction.y)>.1||direction.horizontalDistanceSqr()<3)return false;
        var unit=new Vec3(direction.x,0,direction.z).normalize();
        for(var step:chosenRoute.subList(0,3)){var d=NativeTraversalEvaluator.point(step.to()).subtract(NativeTraversalEvaluator.point(step.from()));if(Math.abs(d.y)>.1||new Vec3(d.x,0,d.z).normalize().dot(unit)<.9||edgeExposure(w,step.to())>0)return false;}
        return p.level().noCollision(p,p.getBoundingBox().move(0,1.3,0).expandTowards(unit.scale(2.5)));
    }
    void reset(){origin=null;selected=null;waypoint=null;waypointNode=null;heading=null;targetAtWaypoint=null;chosenRoute=List.of();exposure.clear();}
}
