package dev.mineagent.runtime.core.objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeMeshTest {
    @Test void staticVisualCanOmitCollisionButDynamicPhysicsStillNeedsIt(){
        String model="{\"version\":2,\"boxes\":[{\"from\":[-1,-2,-1],\"to\":[1,3,1],\"color\":\"#ffffff\"}]}";
        assertTrue(RuntimeMesh.parse(model).collision().nonSolid());assertFalse(RuntimeMesh.parse(model).physics().dynamic());
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(model.replace("\"version\":2","\"version\":2,\"physics\":{\"dynamic\":true,\"mass\":1,\"gravity\":0.04,\"restitution\":0,\"drag\":1}")));
    }
    private static final String BOX="{\"version\":1,\"boxes\":[{\"from\":[-0.5,0,-0.5],\"to\":[0.5,1,0.5],\"color\":\"#339988\"}],\"collision\":[-0.5,0,-0.5,0.5,1,0.5],\"physics\":{\"dynamic\":true,\"mass\":2,\"gravity\":0.04,\"restitution\":0.7,\"drag\":0.98}}";
    @Test void compositeGeometryProducesRealIndexedTrianglesAndIndependentPhysics(){
        var mesh=RuntimeMesh.parse(BOX);assertEquals(12,mesh.triangles().size());assertEquals(24,mesh.vertices().size());assertEquals(2,mesh.physics().mass());assertEquals(1,mesh.collision().height());assertEquals(0xff339988,mesh.triangles().getFirst().color());
    }
    @Test void rawIndexedMeshesRetainTheirVerticesUvsAndMaterialColor(){
        var mesh=RuntimeMesh.parse("{\"version\":1,\"vertices\":[[0,0,0,0,0],[1,0,0,1,0],[0,1,0,0,1]],\"triangles\":[[0,1,2,\"#ffffff\"]],\"collision\":[-1,0,-1,1,1,1]}");assertEquals(3,mesh.vertices().size());assertEquals(1,mesh.triangles().size());assertFalse(mesh.physics().dynamic());
    }
    @Test void malformedOrUnboundedGeometryCannotReachNativeRendering(){
        for(String bad:java.util.List.of(BOX.replace("0.7","1.2"),BOX.replace("[-0.5,0,-0.5,0.5,1,0.5]","[0,0,0,1,1,1]"),BOX.replace("2,\"gravity\"","0,\"gravity\""),BOX.replace("\"version\":1","\"version\":1,\"version\":1"),BOX.replace("#339988","not-a-color"),BOX.replace("\"boxes\"","\"unknown\"")))assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(bad),bad);
        assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse("{\"version\":1,\"vertices\":[[0,0,0,0,0],[1,0,0,1,0],[0,1,0,0,1]],\"triangles\":[[0,1,99,\"#ffffff\"]],\"collision\":[-1,0,-1,1,1,1]}"));
    }
}
