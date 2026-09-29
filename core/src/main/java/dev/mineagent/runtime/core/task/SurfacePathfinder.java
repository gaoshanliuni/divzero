package dev.mineagent.runtime.core.task;
import java.util.*;import java.util.function.*;
/** Resumable search over a shared traversal graph. A slice ending is never proof of no route. */
public final class SurfacePathfinder {
    public record Node(int x,int y16,int z){public double y(){return y16/16.0;}}
    public enum Action { WALK, STEP_UP, JUMP, DROP, CROUCH, OPEN_DOOR, CLIMB, ENTER_WATER, SWIM, LEAVE_WATER }
    public enum Posture { STANDING, CROUCHING, SWIMMING }
    public enum Status { FOUND, BUDGET_EXHAUSTED, WAITING_CHUNKS, NO_PATH, INVALID_TARGET, SEARCH_LIMIT }
    public record PathStep(Node from,Node to,Action action,Posture posture,double cost) {
        public PathStep {Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(action);Objects.requireNonNull(posture);if(!Double.isFinite(cost)||cost<=0)throw new IllegalArgumentException("PATH_COST");}
    }
    public record Result(Status status,List<PathStep> steps,int expanded) {public Result{steps=List.copyOf(steps);}}
    public interface TraversalEvaluator {
        List<PathStep> neighbors(Node from);
        default boolean encounteredUnloaded(){return false;}
    }
    public static final class Search {
        private record Entry(Node node,double cost,double score){}
        private final Node start;private final Predicate<Node> goal;private final ToDoubleFunction<Node> heuristic;private final TraversalEvaluator evaluator;
        private final PriorityQueue<Entry> open=new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        private final Map<Node,Double> costs=new HashMap<>();private final Map<Node,PathStep> parent=new HashMap<>();private final Set<Node> closed=new HashSet<>();
        private Result terminal;
        public Search(Node start,Node goal,TraversalEvaluator evaluator){this(start,goal::equals,n->distance(n,goal),evaluator);}
        public Search(Node start,Predicate<Node> goal,ToDoubleFunction<Node> heuristic,TraversalEvaluator evaluator){this.start=Objects.requireNonNull(start);this.goal=goal;this.heuristic=heuristic;this.evaluator=evaluator;costs.put(start,0.0);open.add(new Entry(start,0,heuristic.applyAsDouble(start)));}
        public Result advance(int budget,BooleanSupplier timeAvailable){
            if(budget<1)throw new IllegalArgumentException("PATH_SLICE_BUDGET");if(terminal!=null)return terminal;int remaining=budget;
            while(!open.isEmpty()&&remaining>0&&timeAvailable.getAsBoolean()){
                var entry=open.remove();var n=entry.node;if(entry.cost>costs.getOrDefault(n,Double.POSITIVE_INFINITY)||!closed.add(n))continue;
                remaining--;if(goal.test(n)){var route=new ArrayList<PathStep>();for(Node at=n;!at.equals(start);){var edge=parent.get(at);route.add(edge);at=edge.from;}Collections.reverse(route);return terminal=new Result(Status.FOUND,route,closed.size());}
                for(var step:evaluator.neighbors(n)){if(!step.from.equals(n))throw new IllegalArgumentException("PATH_EDGE_ORIGIN");var next=step.to;if(closed.contains(next))continue;double cost=entry.cost+step.cost;
                    if(cost<costs.getOrDefault(next,Double.POSITIVE_INFINITY)){costs.put(next,cost);parent.put(next,step);open.add(new Entry(next,cost,cost+heuristic.applyAsDouble(next)));}}
                if(costs.size()>131072)return terminal=new Result(Status.SEARCH_LIMIT,List.of(),closed.size());
            }
            if(open.isEmpty())return terminal=new Result(evaluator.encounteredUnloaded()?Status.WAITING_CHUNKS:Status.NO_PATH,List.of(),closed.size());
            return new Result(Status.BUDGET_EXHAUSTED,List.of(),closed.size());
        }
        public Result advance(int budget){return advance(budget,()->true);}
    }
    /** Compatibility for callers that intentionally use a bounded, one-shot ground query. */
    public static List<Node> find(Node start,Node goal,int budget,Function<Node,List<Node>> neighbors){
        var search=new Search(start,goal,n->{var edges=new ArrayList<PathStep>();for(var next:neighbors.apply(n)){if(Math.abs(next.x-n.x)+Math.abs(next.z-n.z)!=1||next.y16-n.y16>20||n.y16-next.y16>48)continue;edges.add(new PathStep(n,next,Action.WALK,Posture.STANDING,1+Math.abs(next.y()-n.y())*.8));}return edges;});
        var result=search.advance(budget);if(result.status!=Status.FOUND)return List.of();var nodes=new ArrayList<Node>();nodes.add(start);result.steps.forEach(step->nodes.add(step.to));return List.copyOf(nodes);
    }
    private static double distance(Node a,Node b){return Math.abs(a.x-b.x)+Math.abs(a.z-b.z)+Math.abs(a.y()-b.y())*.8;}
    private SurfacePathfinder(){}
}
