package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CombatFootworkTest {
    @Test void holdsDirectionAgainstNoiseButLeavesBlockedOrDangerousSide(){
        var f=new CombatFootwork();assertEquals(1,f.choose(0,2,3));assertEquals(1,f.choose(2,3,2));assertEquals(1,f.choose(10,3,2));assertEquals(-1,f.choose(12,3,2));assertEquals(1,f.choose(13,2,Double.POSITIVE_INFINITY));assertEquals(0,f.choose(14,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY));
        assertEquals(-1,f.choose(15,15,2));
    }
    @Test void changesAttackLineAtBoundedIntervalsAndNeverInventsAJump(){
        var f=new CombatFootwork();assertEquals(1,f.choose(0,2,2));assertEquals(-1,f.choose(12,2,2));assertEquals(-1,f.choose(13,2,2));assertEquals(1,f.choose(24,2,2));
        assertFalse(f.jumpReady(30,true,false,true));assertFalse(f.jumpReady(30,false,true,true));assertFalse(f.jumpReady(30,true,true,false));assertTrue(f.jumpReady(30,true,true,true));f.jumped(30);assertFalse(f.jumpReady(57,true,true,true));assertTrue(f.jumpReady(58,true,true,true));
    }
}
