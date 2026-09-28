package dev.mineagent.runtime.core.geometry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BulldozerSpecTest {
    @Test void sweepRotatesWithoutTouchingFloorAndBoundaryRemainsAuthoritative(){
        var spec=new BulldozerSpec("clear","$viewer","minecraft:overworld",List.of(-5,70,-5),List.of(5,73,5),3,3,4,true,false,false);
        for(int heading=0;heading<4;heading++){var cells=new HashSet<String>();for(int i=0;i<spec.volume();i++){var p=spec.cell(0,70,0,heading,i);assertTrue(p[1]>=70&&p[1]<=72);assertTrue(spec.contains(p[0],p[1],p[2]));assertTrue(cells.add(Arrays.toString(p)));}assertEquals(36,cells.size());}
        assertFalse(spec.contains(0,69,0));assertFalse(spec.contains(6,70,0));assertArrayEquals(new int[]{0,70,3},spec.cell(0,70,0,0,15));
        assertEquals(1,BulldozerSpec.heading(90));assertEquals(3,BulldozerSpec.heading(-90));
    }
    @Test void neverAcceptsUnlimitedImplicitClearingRegion(){
        assertThrows(IllegalArgumentException.class,()->new BulldozerSpec("clear","$viewer","minecraft:overworld",List.of(0,0,0),List.of(2048,10,10),1,1,1,true,false,false));
        assertThrows(IllegalArgumentException.class,()->new BulldozerSpec("../escape","$viewer","minecraft:overworld",List.of(0,0,0),List.of(5,5,5),1,1,1,true,false,false));
        assertThrows(IllegalArgumentException.class,()->BulldozerSpec.heading(Float.NaN));
    }
}
