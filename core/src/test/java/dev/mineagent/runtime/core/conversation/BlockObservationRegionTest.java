package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlockObservationRegionTest {
    @Test void smallRegionAndDelayedObservationAreBounded(){
        var r=new BlockObservationRegion(-4,60,-4,4,64,4,100);assertEquals(405,r.volume());assertDoesNotThrow(()->r.requireNear(0,64,0));
        assertThrows(IllegalArgumentException.class,()->r.requireNear(40,64,0));
        assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(0,0,0,20,20,20,0));
        assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(1,0,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(Integer.MIN_VALUE,0,0,Integer.MAX_VALUE,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(0,0,0,0,0,0,201));
    }
}
