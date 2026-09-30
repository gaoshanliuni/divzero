package dev.mineagent.runtime.core.task;

import java.util.*;
import java.util.function.*;
import dev.mineagent.runtime.core.task.MotionForecast.Point;

/** Discrete drag/gravity interception with swept contact and a bounded set of low/high arcs. */
public final class BallisticIntercept {
    public record Physics(double speed,double gravity,double drag,int maxTicks){public Physics{if(speed<=0||gravity<0||drag<=0||drag>1||maxTicks<1||maxTicks>80)throw new IllegalArgumentException("PROJECTILE_PHYSICS");}}
    public record Shot(Point direction,double flightTicks,double miss){}
    public interface Corridor {boolean clear(Point from,Point to,double time);}
    private record Candidate(Point direction,double time,double error){}
    public static Optional<Shot> solve(Point start,Physics physics,DoubleFunction<Point> target,double radius,Corridor corridor,BooleanSupplier budget){
        return solve(start,physics,new Point(0,0,0),target,radius,corridor,budget);
    }
    public static Optional<Shot> solve(Point start,Physics physics,Point inherited,DoubleFunction<Point> target,double radius,Corridor corridor,BooleanSupplier budget){
        var candidates=new ArrayList<Candidate>();
        for(double time=1;time<=physics.maxTicks;time+=.25){
            if(!budget.getAsBoolean())return Optional.empty();int whole=(int)time;double fraction=time-whole,drag=Math.pow(physics.drag,whole);
            double travel=physics.drag==1?time:(1-drag)/(1-physics.drag)+fraction*drag;
            double falling=physics.drag==1?-physics.gravity*(whole*(whole-1)/2d+fraction*whole):-physics.gravity*((whole-(1-drag)/(1-physics.drag))/(1-physics.drag)+fraction*(1-drag)/(1-physics.drag));
            var delta=target.apply(time).subtract(start);var needed=new Point(delta.x()/travel,(delta.y()-falling)/travel,delta.z()/travel).subtract(inherited);double magnitude=needed.length();
            if(magnitude>.001)candidates.add(new Candidate(needed.scale(1/magnitude),time,Math.abs(magnitude-physics.speed)));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::error).thenComparingDouble(Candidate::time));
        // Both direct and lobbed solutions can survive this ordering; no unbounded angle enumeration.
        for(var candidate:candidates.stream().limit(12).toList()){
            var position=start;var velocity=candidate.direction.scale(physics.speed).add(inherited);
            for(int tick=1;tick<=Math.min(physics.maxTicks,Math.ceil(candidate.time)+2);tick++){
                if(!budget.getAsBoolean())return Optional.empty();var next=position.add(velocity);
                var relative=position.subtract(target.apply(tick-1));var relativeNext=next.subtract(target.apply(tick));var motion=relativeNext.subtract(relative);double square=dot(motion,motion);
                double fraction=square<1e-12?0:Math.clamp(-dot(relative,motion)/square,0,1);double miss=relative.add(motion.scale(fraction)).length();
                var end=miss<=radius?position.add(next.subtract(position).scale(fraction)):next;
                if(!corridor.clear(position,end,tick-1+fraction))break;
                if(miss<=radius)return Optional.of(new Shot(candidate.direction,tick-1+fraction,miss));
                position=next;velocity=new Point(velocity.x()*physics.drag,velocity.y()*physics.drag-physics.gravity,velocity.z()*physics.drag);
            }
        }return Optional.empty();
    }
    private static double dot(Point a,Point b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    private BallisticIntercept(){}
}
