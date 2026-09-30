package dev.mineagent.runtime.core.task;

import java.util.*;

/** Bounded search over ordinary movement and virtual block edits. Nothing here writes a world. */
public final class TerrainPathSearch {
    public record Cell(int x,int y,int z) {
        public Cell add(int x,int y,int z){return new Cell(this.x+x,this.y+y,this.z+z);}
        public int distance(Cell b){return Math.abs(x-b.x)+Math.abs(y-b.y)+Math.abs(z-b.z);}
    }
    public enum Kind { BREAK, PLACE }
    public record Edit(Cell cell,Kind kind,String expected,int ticks) {}
    public record Step(Cell from,Cell to,List<Edit> edits,boolean jumpPlace,double cost) {
        public Step {edits=List.copyOf(edits);}
    }
    public record Block(boolean loaded,boolean clear,boolean supports,boolean breakable,int breakTicks,String state) {}
    public interface World {
        Block block(Cell at);
        boolean canPlace(Cell at);
        /** True only if this location can continue the original intent or a checked escape route. */
        boolean exit(Cell feet,Map<Cell,Kind> changes);
        double risk(Cell feet);
        default double learnedCost(Step step,double risk){return 0;}
    }
    public record Result(String state,List<Step> steps,int expanded,double cost) {
        public Result{steps=List.copyOf(steps);}
    }
    private record State(Cell feet,Map<Cell,Kind> changes,int materials) {}
    private record Entry(State state,List<Step> path,double cost) {}
    private final World world;
    private final Cell origin;
    private final int materials,radius;
    private final PriorityQueue<Entry> open=new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
    private final Map<State,Double> best=new HashMap<>();
    private int expanded;
    private boolean unloaded;
    private Result terminal;
    public TerrainPathSearch(World world,Cell origin,int materials,int radius){
        this.world=Objects.requireNonNull(world);this.origin=origin;this.materials=Math.max(0,materials);this.radius=Math.clamp(radius,1,8);
        var start=new State(origin,Map.of(),0);open.add(new Entry(start,List.of(),0));best.put(start,0d);
    }
    public Result advance(int budget,java.util.function.BooleanSupplier timeAvailable){
        if(terminal!=null)return terminal;
        while(budget-->0&&!open.isEmpty()&&timeAvailable.getAsBoolean()){
            var current=open.remove();if(current.cost>best.getOrDefault(current.state,Double.POSITIVE_INFINITY))continue;
            expanded++;
            if(!current.path.isEmpty()&&world.exit(current.state.feet,current.state.changes))return terminal=new Result("FOUND",current.path,expanded,current.cost);
            if(current.path.size()>=10)continue;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{0,0}}){
                for(int dy=-1;dy<=1;dy++){
                    boolean column=d[0]==0&&d[1]==0;if(column&&dy!=1)continue;
                    var destination=current.state.feet.add(d[0],dy,d[1]);
                    if(Math.abs(destination.x-origin.x)>radius||Math.abs(destination.z-origin.z)>radius||destination.y<origin.y-1||destination.y>origin.y+radius)continue;
                    var changes=new LinkedHashMap<>(current.state.changes);var edits=new ArrayList<Edit>();double cost=8+Math.abs(dy)*5;int used=current.state.materials;
                    // Rising needs head clearance along the whole motion, including the old column.
                    var clearance=new LinkedHashSet<Cell>();clearance.add(destination);clearance.add(destination.add(0,1,0));
                    if(dy>0)clearance.add(current.state.feet.add(0,2,0));
                    boolean valid=true;
                    for(var at:clearance){var block=read(at,changes);if(!block.loaded){valid=false;break;}if(!block.clear){
                        if(column||!block.breakable||block.breakTicks<=0||block.breakTicks>240){valid=false;break;}
                        edits.add(new Edit(at,Kind.BREAK,block.state,block.breakTicks));changes.put(at,Kind.BREAK);cost+=block.breakTicks+4;
                    }}
                    if(!valid)continue;
                    var floor=destination.add(0,-1,0);var support=read(floor,changes);if(!support.loaded)continue;
                    if(!support.supports){
                        if(!support.clear||!world.canPlace(floor)||used>=materials||dy<0)continue;
                        edits.add(new Edit(floor,Kind.PLACE,support.state,12));changes.put(floor,Kind.PLACE);used++;cost+=22;
                    } else if(column)continue;
                    if(changes.size()>10)continue;
                    double risk=world.risk(destination);if(!Double.isFinite(risk)||risk<0)continue;
                    // Price exposure for the complete edit-and-move interval, not just the endpoint.
                    cost+=Math.max(risk,world.risk(current.state.feet))*Math.max(1,cost/10);
                    var step=new Step(current.state.feet,destination,edits,column,cost);
                    double learned=world.learnedCost(step,risk);if(Double.isFinite(learned))cost+=Math.clamp(learned,0,10);
                    step=new Step(step.from,step.to,step.edits,step.jumpPlace,cost);
                    var next=new State(destination,Map.copyOf(changes),used);double total=current.cost+cost;
                    if(total>=best.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                    best.put(next,total);var path=new ArrayList<>(current.path);path.add(step);open.add(new Entry(next,List.copyOf(path),total));
                }
            }
            if(expanded>=4096||best.size()>16384)return terminal=new Result("SEARCH_LIMIT",List.of(),expanded,0);
        }
        if(open.isEmpty())return terminal=new Result(unloaded?"WAITING_CHUNKS":"NO_SAFE_ESCAPE",List.of(),expanded,0);
        return new Result("SEARCHING",List.of(),expanded,0);
    }
    private Block read(Cell cell,Map<Cell,Kind> changes){
        var value=world.block(cell);if(!value.loaded)unloaded=true;
        return switch(changes.get(cell)){case BREAK->new Block(value.loaded,true,false,false,0,value.state);case PLACE->new Block(value.loaded,false,true,false,0,value.state);case null->value;};
    }
}
