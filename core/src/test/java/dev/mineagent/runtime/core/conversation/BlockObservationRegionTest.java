package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class BlockObservationRegionTest {
 @Test void largeRegionUsesLongCursorWithoutAllocatingOrDiscarding(){var r=new BlockObservationRegion(0,0,0,4095,4095,4095,0);assertEquals(68719476736L,r.volume());assertArrayEquals(new int[]{4095,4095,4095},r.at(r.volume()-1));assertArrayEquals(new int[]{0,0,1},r.at(4096));assertDoesNotThrow(()->r.requireNear(10000,64,10000));assertThrows(IllegalArgumentException.class,()->r.at(r.volume()));assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(0,0,0,4096,0,0,0));assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(Integer.MIN_VALUE,0,0,Integer.MAX_VALUE,0,0,0));assertThrows(IllegalArgumentException.class,()->new BlockObservationRegion(1,0,0,0,0,0,0));}
}
