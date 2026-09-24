package dev.mineagent.runtime.core.task;
import java.util.*;import java.util.function.Function;
/** Quantized 1/16-block floor heights, not integer empty-voxel navigation. */
public final class SurfacePathfinder {
 public record Node(int x,int y16,int z){public double y(){return y16/16.0;}}
 public static List<Node> find(Node start,Node goal,int budget,Function<Node,List<Node>> neighbors){record Entry(Node node,double score){}var open=new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::score));var costs=new HashMap<Node,Double>();var parent=new HashMap<Node,Node>();var closed=new HashSet<Node>();costs.put(start,0.0);open.add(new Entry(start,distance(start,goal)));while(!open.isEmpty()&&closed.size()<budget){var n=open.remove().node;if(!closed.add(n))continue;if(n.equals(goal)){var route=new ArrayList<Node>();for(Node at=n;at!=null;at=parent.get(at))route.add(at);Collections.reverse(route);return List.copyOf(route);}for(var next:neighbors.apply(n)){if(closed.contains(next)||Math.abs(next.x-n.x)+Math.abs(next.z-n.z)!=1||next.y16-n.y16>20||n.y16-next.y16>48)continue;double cost=costs.get(n)+1+Math.abs(next.y()-n.y())*.8;if(cost<costs.getOrDefault(next,Double.POSITIVE_INFINITY)){costs.put(next,cost);parent.put(next,n);open.add(new Entry(next,cost+distance(next,goal)));}}}return List.of();}
 private static double distance(Node a,Node b){return Math.abs(a.x-b.x)+Math.abs(a.z-b.z)+Math.abs(a.y()-b.y())*.8;}
 private SurfacePathfinder(){}
}
