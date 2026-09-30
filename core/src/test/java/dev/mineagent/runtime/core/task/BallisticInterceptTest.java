package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.mineagent.runtime.core.task.MotionForecast.Point;
class BallisticInterceptTest {
    @Test void leadsHorizontalAndVerticalMotionWithNativeDrag(){
        var shot=BallisticIntercept.solve(new Point(0,1.6,0),new BallisticIntercept.Physics(3,.05,.99,40),t->new Point(18+t*.15,1.3+t*.08,2+t*.2),.4,(a,b,t)->true,()->true).orElseThrow();
        assertTrue(shot.direction().z()>2d/18);assertTrue(shot.direction().y()>0);assertTrue(shot.flightTicks()>5);assertTrue(shot.miss()<.4);
    }
    @Test void blockedCorridorsOrBudgetExhaustionNeverBecomePermissionToFire(){
        var physics=new BallisticIntercept.Physics(3,.05,.99,40);var start=new Point(0,1.6,0);
        assertTrue(BallisticIntercept.solve(start,physics,t->new Point(10,1.6,0),.4,(a,b,t)->false,()->true).isEmpty());
        assertTrue(BallisticIntercept.solve(start,physics,t->new Point(10,1.6,0),.4,(a,b,t)->true,()->false).isEmpty());
    }
}
