package dev.mineagent.runtime.client.control;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TakeoverCameraRotationTest {
    @Test void freeObserverStaysIndependentWhileBodyKeepsTurning() {
        var camera = new TakeoverCameraRotation();
        camera.reset(40, 10);
        camera.tick(60, 20);
        camera.turn(camera.sample(.5), 90, -30);
        assertTrue(camera.observingFreely());
        camera.tick(-160, -45);
        assertEquals(140, camera.sample(.1).yaw(), .0001);
        assertEquals(-15, camera.sample(.9).pitch(), .0001);
        camera.follow(-160, -45);
        assertFalse(camera.observingFreely());
        assertEquals(-160, camera.sample(0).yaw(), .0001);
        camera.tick(-140, -25);
        assertEquals(-150, camera.sample(.5).yaw(), .0001);
    }

    @Test void repeatedDragsWrapYawClampPitchAndContextResetDropsFreeView() {
        var camera = new TakeoverCameraRotation();
        camera.reset(170, 0);
        camera.turn(camera.sample(1), 30, 150);
        assertEquals(-160, camera.sample(1).yaw(), .0001);
        assertEquals(90, camera.sample(1).pitch(), .0001);
        camera.turn(new TakeoverCameraRotation.Angles(0, 0), -400, -200);
        assertEquals(160, camera.sample(1).yaw(), .0001);
        assertEquals(-90, camera.sample(1).pitch(), .0001);
        camera.turn(camera.sample(1), Double.NaN, 0);
        assertEquals(160, camera.sample(1).yaw(), .0001);
        camera.reset(12, 4);
        assertFalse(camera.observingFreely());
        assertEquals(12, camera.sample(1).yaw(), .0001);
    }

    @Test void rendersIntermediateAnglesWithoutConsumingOrChangingTheBodyTick() {
        var camera = new TakeoverCameraRotation();
        camera.reset(10, -6);
        camera.tick(25, 6);
        assertEquals(10, camera.sample(0).yaw(), .0001);
        assertEquals(13.75, camera.sample(.25).yaw(), .0001);
        assertEquals(17.5, camera.sample(.5).yaw(), .0001);
        assertEquals(0, camera.sample(.5).pitch(), .0001);
        assertEquals(25, camera.sample(1).yaw(), .0001);
        assertEquals(17.5, camera.sample(.5).yaw(), .0001);
        camera.tick(40, 6);
        assertEquals(25, camera.sample(0).yaw(), .0001);
    }

    @Test void crossesBothYawSeamsOnTheShortArc() {
        var camera = new TakeoverCameraRotation();
        camera.reset(179, 0);
        camera.tick(-179, 0);
        assertEquals(180, camera.sample(.5).yaw(), .0001);
        camera.reset(-179, 0);
        camera.tick(179, 0);
        assertEquals(-180, camera.sample(.5).yaw(), .0001);
    }

    @Test void idleSettlesAndNewContextDropsTheOldCameraPath() {
        var camera = new TakeoverCameraRotation();
        camera.reset(0, 0);
        camera.tick(15, 12);
        camera.tick(15, 12);
        assertEquals(15, camera.sample(.2).yaw(), .0001);
        camera.reset(-90, -45);
        assertEquals(-90, camera.sample(.2).yaw(), .0001);
        assertEquals(-45, camera.sample(2).pitch(), .0001);
    }
}
