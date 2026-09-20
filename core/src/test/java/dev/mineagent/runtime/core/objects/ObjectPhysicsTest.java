package dev.mineagent.runtime.core.objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObjectPhysicsTest {
    @Test void bounceUsesActualCollisionAxesAndCannotInventEnergy(){
        var p=new RuntimeMesh.Physics(true,1,0.04,0.5,0.9);var incoming=new ObjectPhysics.Motion(1,-2,0.5);
        var after=ObjectPhysics.bounce(incoming,false,true,false,p);assertEquals(0.9,after.x(),1e-6);assertEquals(0.9,after.y(),1e-6);assertEquals(0.45,after.z(),1e-6);
        assertEquals(-2.04,ObjectPhysics.gravity(incoming,p).y(),1e-6);
    }
    @Test void springForceAndSpeedAreBoundedAndStaticBodiesDoNotAdvance(){
        var p=new RuntimeMesh.Physics(true,2,0,0.5,1);var motion=ObjectPhysics.spring(new ObjectPhysics.Motion(0,0,0),new ObjectPhysics.Motion(100,0,0),0.2,0.1,p);assertTrue(motion.x()>0&&motion.x()<=ObjectPhysics.MAX_SPEED);
        assertEquals(new ObjectPhysics.Motion(0,0,0),ObjectPhysics.gravity(motion,new RuntimeMesh.Physics(false,1,0,0,1)));
        assertThrows(IllegalArgumentException.class,()->new ObjectPhysics.Motion(Double.NaN,0,0));
    }
    @Test void restingGravityContactDoesNotCreateAnEndlessMicroBounce(){
        var p=new RuntimeMesh.Physics(true,1,0.04,0.7,0.99);
        assertEquals(0,ObjectPhysics.settleVertical(0.028,-0.04,true,p),1e-9);
        assertEquals(0.35,ObjectPhysics.settleVertical(0.35,-0.5,true,p),1e-9);
        assertEquals(-0.04,ObjectPhysics.settleVertical(-0.04,-0.04,false,p),1e-9);
    }
}
