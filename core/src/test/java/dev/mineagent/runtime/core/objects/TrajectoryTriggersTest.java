package dev.mineagent.runtime.core.objects;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TrajectoryTriggersTest {
    private final RuntimeMesh.Collision box=new RuntimeMesh.Collision(.4,.4,.4);
    private TrajectoryTriggers.Point p(double x,double y,double z){return new TrajectoryTriggers.Point(x,y,z);}
    @Test void fullBodyMustClearDownwardAndFitAperture(){
        assertTrue(TrajectoryTriggers.downwardCircle(List.of(p(0,5,0),p(0,1,0)),0,3,0,.5,box));
        assertFalse(TrajectoryTriggers.downwardCircle(List.of(p(0,1,0),p(0,5,0)),0,3,0,.5,box));
        assertFalse(TrajectoryTriggers.downwardCircle(List.of(p(0,4,0),p(0,2.8,0)),0,3,0,.5,box));
        assertFalse(TrajectoryTriggers.downwardCircle(List.of(p(.4,5,0),p(.4,1,0)),0,3,0,.5,box));
        assertFalse(TrajectoryTriggers.downwardCircle(List.of(p(0,1,0),p(0,0,0)),0,3,0,.5,box));
    }
    @Test void useActualSegmentsRatherThanChordAcrossBounce(){
        assertFalse(TrajectoryTriggers.downwardCircle(List.of(p(-2,5,0),p(-2,1,0),p(2,1,0)),0,3,0,.5,box));
        assertTrue(TrajectoryTriggers.downwardCircle(List.of(p(-2,5,0),p(0,5,0),p(0,1,0)),0,3,0,.5,box));
        assertThrows(IllegalArgumentException.class,()->TrajectoryTriggers.downwardCircle(List.of(),0,3,0,Double.NaN,box));
    }
    @Test void visualOnlyIsExplicitStaticWorldOnlyAndOldBindingsStaySolid()throws Exception{
        String s=ParametricMeshesTest.source(ParametricMeshesTest.SPHERE);
        assertFalse(RuntimeMesh.parse(s).collision().nonSolid());
        String visual=s.replace("\"version\":2","\"version\":2,\"collisionMode\":\"none\"");
        assertTrue(RuntimeMesh.parse(visual).collision().nonSolid());
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(visual.replace("\"version\":2","\"version\":2,\"physics\":{\"dynamic\":true,\"mass\":1,\"gravity\":0.04,\"restitution\":0.5,\"drag\":0.99}")));
        assertThrows(IllegalArgumentException.class,()->ModelGeometryTools.inspect(visual,"item"));
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(visual.replace("\"none\"","\"mesh\"")));
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(visual.replace("\"version\":2","\"version\":1")));
        var legacy=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"width\":1,\"height\":2,\"depth\":1}",RuntimeMesh.Collision.class);assertFalse(legacy.nonSolid());
    }
}
