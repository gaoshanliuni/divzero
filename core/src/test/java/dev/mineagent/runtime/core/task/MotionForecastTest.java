package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mineagent.runtime.core.task.MotionForecast.*;
class MotionForecastTest {
    private static final Collision FLOOR=new Collision(){public Point move(Point a,Point v){var n=a.add(v);return new Point(n.x(),Math.max(0,n.y()),n.z());}public boolean supported(Point a){return a.y()<=.001;}};
    @Test void fallingOpponentThreatIncludesAirAndLandingWithTurningBranches(){
        var paths=predict(new Input(new Point(0,1,0),new Point(.25,-.15,0),false,.08,.05,0),FLOOR,12);
        assertEquals(5,paths.size());assertTrue(paths.getFirst().stream().anyMatch(s->!s.grounded()));assertTrue(paths.getFirst().getLast().grounded());
        assertNotEquals(paths.get(1).getLast().position().z(),paths.get(2).getLast().position().z());
        assertTrue(paths.getFirst().getLast().position().x()>paths.getFirst().stream().filter(Sample::grounded).findFirst().orElseThrow().position().x());
    }
    @Test void missingObservationsIncreaseUncertaintyAndCollisionStopsTravel(){
        Collision wall=new Collision(){public Point move(Point a,Point v){return a.x()+v.x()>1?a:a.add(v);}public boolean supported(Point a){return true;}};
        var old=predict(new Input(new Point(0,0,0),new Point(.4,0,0),true,.08,.03,10),wall,8);
        var fresh=predict(new Input(new Point(0,0,0),new Point(.4,0,0),true,.08,.03,0),wall,8);
        assertTrue(old.getFirst().getLast().position().x()<=1);assertTrue(old.getFirst().getLast().uncertainty()>fresh.getFirst().getLast().uncertainty());
    }
}
