package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AutonomyPlanTest {
    @Test void uiResumeDoesNotReplayFailedRequestsOrReplanUnchangedIdle(){
        for(double moved:new double[]{0,.24,.25,10,Double.POSITIVE_INFINITY})
            assertFalse(AutonomySessionPolicy.reobserveOnResume("WAITING_FOR_INSTRUCTION",moved));
        assertFalse(AutonomySessionPolicy.reobserveOnResume("IDLE",0));
        assertFalse(AutonomySessionPolicy.reobserveOnResume("IDLE",.24));
        assertTrue(AutonomySessionPolicy.reobserveOnResume("IDLE",.25));
        assertTrue(AutonomySessionPolicy.reobserveOnResume("IDLE",Double.NaN));
        assertTrue(AutonomySessionPolicy.reobserveOnResume("ACTING",0));
    }
    @Test void strictJsonRejectsDuplicateFieldsTrailingValuesAndIntegerOverflow(){
        String valid="{\"action\":\"WAIT\",\"target\":[0,0,0],\"ticks\":20,\"slot\":0,\"summary\":\"等候\"}";
        assertThrows(Exception.class,()->AutonomyPlan.parse(valid+" {}"));
        assertThrows(Exception.class,()->AutonomyPlan.parse(valid.replace("20","4294967316")));
        assertThrows(Exception.class,()->AutonomyPlan.parse(valid.replace("\"ticks\":20","\"ticks\":20,\"ticks\":30")));
        assertThrows(Exception.class,()->AutonomyPlan.parse(valid.replace("[0,0,0]","[\"0\",0,0]")));
    }
    @Test void goalsAreNotAKeyboardMacroAndInvalidActionsCannotEnterTheWire()throws Exception{var plan=AutonomyPlan.parse("{\"action\":\"MOVE\",\"target\":[1.5,64,3.5],\"ticks\":20,\"slot\":0,\"summary\":\"走向目标\"}");assertEquals("MOVE",plan.action());assertThrows(IllegalArgumentException.class,()->new AutonomyPlan("COMMAND",List.of(0d,0d,0d),20,0,"bad"));assertThrows(Exception.class,()->AutonomyPlan.parse("{\"steps\":[]}"));assertThrows(IllegalArgumentException.class,()->new AutonomyPlan("MOVE",List.of(Double.NaN,0d,0d),20,0,"bad"));}
    @Test void liveGridChangesProduceANewRouteAndNeverLoadUnknownCells(){var start=new ObservedPathfinder.Cell(0,64,0);var end=new ObservedPathfinder.Cell(6,64,0);var blocked=new HashSet<ObservedPathfinder.Cell>();ObservedPathfinder.Grid grid=c->c.y()==64&&Math.abs(c.z())<=3&&!blocked.contains(c);var first=ObservedPathfinder.find(start,end,grid);assertEquals(6,first.size());blocked.add(new ObservedPathfinder.Cell(3,64,0));var second=ObservedPathfinder.find(start,end,grid);assertFalse(second.contains(new ObservedPathfinder.Cell(3,64,0)));assertTrue(second.size()>first.size());assertTrue(second.stream().anyMatch(c->c.z()!=0));assertTrue(ObservedPathfinder.find(start,new ObservedPathfinder.Cell(30,64,0),grid).isEmpty());}
    @Test void unsafeTransitionsAreNotAccepted(){ObservedPathfinder.Grid g=new ObservedPathfinder.Grid(){public boolean standable(ObservedPathfinder.Cell c){return c.equals(new ObservedPathfinder.Cell(0,0,0))||c.equals(new ObservedPathfinder.Cell(1,1,0));}public boolean transition(ObservedPathfinder.Cell a,ObservedPathfinder.Cell b){return false;}};assertTrue(ObservedPathfinder.find(new ObservedPathfinder.Cell(0,0,0),new ObservedPathfinder.Cell(1,1,0),g).isEmpty());}
}
