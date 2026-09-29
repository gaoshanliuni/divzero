package dev.mineagent.runtime.core.task;
import java.util.*;
/** One bounded breadth-first exploration answers many work-point queries with the route evaluator. */
public final class SurfaceReachability {
    public enum Reachability { REACHABLE, UNKNOWN, UNREACHABLE }
    private final SurfacePathfinder.TraversalEvaluator evaluator;private final Deque<SurfacePathfinder.Node> open=new ArrayDeque<>();private final Set<SurfacePathfinder.Node> reached=new LinkedHashSet<>();
    public SurfaceReachability(SurfacePathfinder.Node origin,SurfacePathfinder.TraversalEvaluator evaluator){this.evaluator=evaluator;reached.add(origin);open.add(origin);}
    public int advance(int budget,java.util.function.BooleanSupplier timeAvailable){int expanded=0;while(!open.isEmpty()&&expanded<budget&&timeAvailable.getAsBoolean()){var n=open.removeFirst();expanded++;for(var edge:evaluator.neighbors(n))if(reached.add(edge.to()))open.addLast(edge.to());}return expanded;}
    public List<SurfacePathfinder.Node> reached(){return List.copyOf(reached);}
    public Reachability state(SurfacePathfinder.Node node){if(reached.contains(node))return Reachability.REACHABLE;return open.isEmpty()&&!evaluator.encounteredUnloaded()?Reachability.UNREACHABLE:Reachability.UNKNOWN;}
    public boolean exhausted(){return open.isEmpty();}
}
