package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded multi-exit exploration. Score whole routes and onward space, not only endpoints. */
final class CombatPositioning {
    record Route(Node node,List<PathStep> steps,double worstRisk){}
    private NativeTraversalEvaluator evaluator;private final Deque<Route> open=new ArrayDeque<>();private final Set<Node> seen=new HashSet<>();private final List<Route> candidates=new ArrayList<>();
    private Vec3 origin;private int started;private String purpose="";private Vec3 selected;
    Vec3 choose(SkillWork w,String intent,double desiredDistance){
        if(origin==null||w.tick()-started>8||origin.distanceToSqr(w.player().position())>1||!purpose.equals(intent)){
            evaluator=new NativeTraversalEvaluator(w.player());origin=w.player().position();started=w.tick();purpose=intent;selected=null;open.clear();seen.clear();candidates.clear();var node=evaluator.closest(origin);if(node!=null){seen.add(node);open.add(new Route(node,List.of(),w.combat.risk(w,origin)));}
        }
        var budget=NativeNavigationBudget.get(w.runtime.server);int allowed=budget.claim(w.token(),w.tick());evaluator.beginSlice();
        for(int i=0;i<allowed&&!open.isEmpty()&&budget.timeAvailable();i++){
            var route=open.removeFirst();var point=NativeTraversalEvaluator.point(route.node);if(route.steps.size()>=2&&evaluator.neighbors(route.node).stream().filter(edge->!route.steps.stream().anyMatch(step->step.from().equals(edge.to()))).count()>=2)candidates.add(route);
            if(route.steps.size()>=7)continue;
            for(var edge:evaluator.neighbors(route.node)){
                var next=NativeTraversalEvaluator.point(edge.to());if(Math.abs(next.y-origin.y)>1.25||next.distanceToSqr(origin)>49||edge.action()==Action.DROP||!seen.add(edge.to()))continue;
                if(next.distanceTo(w.combat.center(w))>w.session.spec().combat().leash())continue;
                if(!w.player().level().noCollision(w.player(),w.player().getDimensions(w.player().getPose()).makeBoundingBox(next).deflate(.05)))continue;
                var steps=new ArrayList<>(route.steps);steps.add(edge);open.add(new Route(edge.to(),List.copyOf(steps),Math.max(route.worstRisk,w.combat.risk(w,next))));
            }
        }
        double best=Double.POSITIVE_INFINITY;var target=w.combat.selected;
        for(var route:candidates){var point=NativeTraversalEvaluator.point(route.node);double risk=w.combat.risk(w,point);double routeRisk=route.steps.stream().mapToDouble(step->w.combat.risk(w,NativeTraversalEvaluator.point(step.to()))).max().orElse(risk);double score=risk*2+routeRisk*.6+route.steps.size()*.15;
            if(target!=null)score+=Math.abs(point.distanceTo(target.position())-desiredDistance)*(intent.equals("APPROACH")?5:1.1);
            if(intent.equals("RETREAT")||intent.equals("RECOVER")||intent.equals("LURE")){
                score-=origin.distanceTo(point)*.45;
                if(w.combat.protectedEntity!=null&&point.distanceTo(w.combat.protectedEntity.position())<origin.distanceTo(w.combat.protectedEntity.position())-.5)score+=100;
                var area=w.session.spec().area();if(w.session.spec().kind()==dev.mineagent.runtime.core.task.SkillSpec.Kind.GUARD&&area!=null){var center=new Vec3((area.min().x()+area.max().x())/2,(area.min().y()+area.max().y())/2,(area.min().z()+area.max().z())/2);if(point.distanceToSqr(center)<origin.distanceToSqr(center)-1)score+=30;}
            }
            if(target!=null&&intent.equals("LURE")){var a=origin.subtract(target.position()).normalize();var b=point.subtract(target.position()).normalize();score-=Math.abs(a.x*b.z-a.z*b.x)*2;}
            // Execute the first checked edge; do not hand an endpoint to a different route search.
            if(score<best){best=score;selected=NativeTraversalEvaluator.point(route.steps.getFirst().to());}
        }
        return selected;
    }
    void reset(){origin=null;selected=null;}
}
