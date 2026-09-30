package dev.mineagent.runtime.core.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DamageSweepTest {
    private static DamageSweep.Box box(double x,double z,double size){return new DamageSweep.Box(x,1,z,size,.5,size);}
    @Test void fastProjectileCannotTunnelThroughBody(){
        assertTrue(DamageSweep.intersects(box(0,0,.3),box(0,0,.3),box(-8,0,.1),box(8,0,.1)));
    }
    @Test void crossingRoutesAtDifferentTimesAreNotHits(){
        assertFalse(DamageSweep.intersects(box(-2,0,.05),box(2,0,.05),box(0,-1,.05),box(0,3,.05)));
        assertTrue(DamageSweep.intersects(box(-2,0,.05),box(2,0,.05),box(0,-2,.05),box(0,2,.05)));
    }
    @Test void movingTogetherDoesNotInventAHit(){
        assertFalse(DamageSweep.intersects(box(0,0,.3),box(3,0,.3),box(2,0,.1),box(5,0,.1)));
    }
    @Test void wholeBodyAndVerticalClearanceMatter(){
        var body=new DamageSweep.Box(0,.9,0,.3,.9,.3);
        assertFalse(DamageSweep.intersects(body,body,new DamageSweep.Box(-2,2.5,0,.05,.05,.05),new DamageSweep.Box(2,2.5,0,.05,.05,.05)));
        assertTrue(DamageSweep.intersects(body,body,new DamageSweep.Box(-2,1.6,0,.05,.05,.05),new DamageSweep.Box(2,1.6,0,.05,.05,.05)));
    }
}
