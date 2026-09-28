package dev.mineagent.runtime.client.preview;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeSceneTest {
    private static final String TRIANGLE="{\"title\":\"Sample\",\"vertices\":[[0,0,0],[1,0,0],[0,1,1]],\"faces\":[[0,1,2,4294967295]]}";
    @Test void cameraRotationAndPanChangeProjectionWithoutChangingMesh()throws Exception{
        var scene=NativeScene.parse(TRIANGLE);var first=scene.project(new NativeScene.Camera(0,0,1,0,0),400,300);var moved=scene.project(new NativeScene.Camera(0,0,1,12,-8),400,300);for(int i=0;i<6;i++)assertEquals(first.xy()[i]+(i%2==0?12:-8),moved.xy()[i],.001);assertNotEquals(first.xy()[0],scene.project(new NativeScene.Camera(1,.3,1,0,0),400,300).xy()[0]);assertEquals(1,scene.triangleCount());
    }
    @Test void rejectsBadIndicesCoordinatesAndCamera()throws Exception{
        assertThrows(IllegalArgumentException.class,()->NativeScene.parse(TRIANGLE.replace("[0,1,2,","[0,1,3,")));
        assertThrows(IllegalArgumentException.class,()->NativeScene.parse(TRIANGLE.replace("[1,0,0]","[1e309,0,0]")));
        assertThrows(IllegalArgumentException.class,()->new NativeScene.Camera(0,0,0,0,0));
        for(float value:NativeScene.parse(TRIANGLE).project(new NativeScene.Camera(2,1.2,6,0,0),640,400).xy())assertTrue(Float.isFinite(value));
    }
}
