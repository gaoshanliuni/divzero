package dev.mineagent.runtime.core.task;
import java.util.*;
/** Bounded A* over a fresh, loaded standability observation. It neither loads chunks nor moves an entity. */
public final class ObservedPathfinder {
    private ObservedPathfinder(){}
    public record Cell(int x,int y,int z){}
    public interface Grid {boolean standable(Cell cell);default boolean transition(Cell from,Cell to){return true;}}
    private record Node(Cell cell,double cost,double rank){}
    public static List<Cell> find(Cell start,Cell goal,Grid grid){if(Math.abs(goal.x-start.x)>16||Math.abs(goal.z-start.z)>16||Math.abs(goal.y-start.y)>8||!grid.standable(goal))return List.of();var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::rank));var costs=new HashMap<Cell,Double>();var parents=new HashMap<Cell,Cell>();queue.add(new Node(start,0,distance(start,goal)));costs.put(start,0.0);int visited=0;while(!queue.isEmpty()&&visited++<1024){var n=queue.remove();if(n.cost>costs.getOrDefault(n.cell,Double.POSITIVE_INFINITY))continue;if(n.cell.equals(goal)){var path=new ArrayList<Cell>();Cell p=goal;while(!p.equals(start)){path.add(p);p=parents.get(p);if(p==null||path.size()>128)return List.of();}Collections.reverse(path);return List.copyOf(path);}for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int dy:new int[]{0,1,-1}){var c=new Cell(n.cell.x+d[0],n.cell.y+dy,n.cell.z+d[1]);if(Math.abs(c.x-start.x)>16||Math.abs(c.z-start.z)>16||Math.abs(c.y-start.y)>8||!grid.standable(c)||!grid.transition(n.cell,c))continue;double cost=n.cost+1+Math.abs(dy)*.5;if(cost<costs.getOrDefault(c,Double.POSITIVE_INFINITY)){costs.put(c,cost);parents.put(c,n.cell);queue.add(new Node(c,cost,cost+distance(c,goal)));}break;}}return List.of();}
    private static double distance(Cell a,Cell b){return Math.abs(a.x-b.x)+Math.abs(a.z-b.z)+Math.abs(a.y-b.y)*.5;}
}
