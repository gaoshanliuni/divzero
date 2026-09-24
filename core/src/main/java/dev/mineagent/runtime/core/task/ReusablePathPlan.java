package dev.mineagent.runtime.core.task;
import java.util.*;
/** Feet coordinates. The permanent floor is one block below each cell. */
public record ReusablePathPlan(List<Cell> feet, boolean pillar) {
 public record Cell(int x,int y,int z){}
 public ReusablePathPlan {feet=List.copyOf(feet);}
 public static ReusablePathPlan between(Cell a,Cell b){
  long dx=Math.abs((long)b.x-a.x),dy=Math.abs((long)b.y-a.y),dz=Math.abs((long)b.z-a.z);
  if(Math.max(dx,Math.max(dy,dz))>=2048)throw new IllegalArgumentException("PATH_BOUNDS_2048");
  if(Math.max(Math.abs((long)a.y),Math.abs((long)b.y))>30000000)throw new IllegalArgumentException("PATH_WORLD_BOUNDS");
  if(Math.max(Math.abs((long)a.x),Math.abs((long)b.x))>30000000||Math.max(Math.abs((long)a.z),Math.abs((long)b.z))>30000000)throw new IllegalArgumentException("PATH_WORLD_BOUNDS");
  int steps=(int)(dx+dz);boolean pillar=steps==0&&b.y>a.y;
  if(steps==0&&b.y<a.y||steps>0&&dy>steps)throw new IllegalArgumentException("PATH_SLOPE_NEEDS_LONGER_ROUTE_OR_PILLAR");
  var cells=new ArrayList<Cell>();cells.add(a);if(pillar){for(int y=a.y+1;y<=b.y;y++)cells.add(new Cell(a.x,y,a.z));}
  else {int x=a.x,z=a.z;for(int i=1;i<=steps;i++){if(x!=b.x&&(z==b.z||(long)i*dx/steps>Math.abs((long)x-a.x)))x+=Integer.signum(b.x-x);else z+=Integer.signum(b.z-z);int y=a.y+(int)Math.round((b.y-a.y)*(double)i/Math.max(1,steps));cells.add(new Cell(x,y,z));}}
  return new ReusablePathPlan(cells,pillar);
 }
}
