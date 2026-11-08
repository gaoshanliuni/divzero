package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class SurfacePathfinderTest {
 @Test void searchBudgetIsConfiguredSeparatelyFromTickSlices(){
  var start=new SurfacePathfinder.Node(0,0,0);var goal=new SurfacePathfinder.Node(20,0,0);
  SurfacePathfinder.TraversalEvaluator e=n->List.of(new SurfacePathfinder.PathStep(n,new SurfacePathfinder.Node(n.x()+1,0,0),SurfacePathfinder.Action.WALK,SurfacePathfinder.Posture.STANDING,1));
  assertEquals(SurfacePathfinder.Status.SEARCH_LIMIT,new SurfacePathfinder.Search(start,goal,e).searchBudget(()->5).advance(100).status());
  assertEquals(SurfacePathfinder.Status.FOUND,new SurfacePathfinder.Search(start,goal,e).searchBudget(()->30).advance(100).status());
  var config=dev.mineagent.runtime.core.config.NavigationResourceBudget.from(Map.of());
  assertEquals(8_000_000,config.nanosFor(10_000_000));assertEquals(1_000_000,config.nanosFor(70_000_000));
  assertEquals(625L,new dev.mineagent.runtime.core.config.RuntimeResourceLimits(100,1000,12).ticketsPerAgent());
 }
 @Test void halfSlabCarpetAndStepRemainDistinct(){var a=new SurfacePathfinder.Node(0,160,0);var b=new SurfacePathfinder.Node(1,168,0);var c=new SurfacePathfinder.Node(2,169,0);var d=new SurfacePathfinder.Node(3,185,0);var links=Map.of(a,List.of(b),b,List.of(c),c,List.of(d),d,List.<SurfacePathfinder.Node>of());assertEquals(List.of(a,b,c,d),SurfacePathfinder.find(a,d,20,n->links.getOrDefault(n,List.of())));}
 @Test void fenceHeightJumpIsRejectedAndNoUnboundedSearch(){var a=new SurfacePathfinder.Node(0,0,0);var fence=new SurfacePathfinder.Node(1,24,0);assertTrue(SurfacePathfinder.find(a,fence,20,n->List.of(fence)).isEmpty());}
}
