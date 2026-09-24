package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class SurfacePathfinderTest {
 @Test void halfSlabCarpetAndStepRemainDistinct(){var a=new SurfacePathfinder.Node(0,160,0);var b=new SurfacePathfinder.Node(1,168,0);var c=new SurfacePathfinder.Node(2,169,0);var d=new SurfacePathfinder.Node(3,185,0);var links=Map.of(a,List.of(b),b,List.of(c),c,List.of(d),d,List.<SurfacePathfinder.Node>of());assertEquals(List.of(a,b,c,d),SurfacePathfinder.find(a,d,20,n->links.getOrDefault(n,List.of())));}
 @Test void fenceHeightJumpIsRejectedAndNoUnboundedSearch(){var a=new SurfacePathfinder.Node(0,0,0);var fence=new SurfacePathfinder.Node(1,24,0);assertTrue(SurfacePathfinder.find(a,fence,20,n->List.of(fence)).isEmpty());}
}
