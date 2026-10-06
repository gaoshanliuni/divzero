package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TraversalSafetyTest {
    @Test void refusesLethalLargeAndUnknownDrops(){
        assertTrue(TraversalSafety.affordableDrop(4,1,20,0));
        assertFalse(TraversalSafety.affordableDrop(4,1,4,0));
        assertTrue(TraversalSafety.affordableDrop(4,1,4,2));
        assertFalse(TraversalSafety.affordableDrop(10,7,20,20));
        assertFalse(TraversalSafety.affordableDrop(25,0,20,0));
        assertFalse(TraversalSafety.affordableDrop(4,Double.NaN,20,0));
    }
    @Test void diagonalInputPreservesWorldDirectionForEveryHeading(){
        for(int heading=-180;heading<=180;heading+=7)for(int facing=-180;facing<=180;facing+=19){
            double dx=Math.cos(Math.toRadians(heading)),dz=Math.sin(Math.toRadians(heading));
            var input=TraversalSafety.diagonal(dx,dz,facing);double yaw=Math.toRadians(input.yaw());
            double x=(input.strafe()*Math.cos(yaw)-input.forward()*Math.sin(yaw))/Math.sqrt(2);
            double z=(input.forward()*Math.cos(yaw)+input.strafe()*Math.sin(yaw))/Math.sqrt(2);
            assertEquals(dx,x,1e-5);assertEquals(dz,z,1e-5);
        }
    }
}
