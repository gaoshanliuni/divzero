package dev.mineagent.runtime.core.objects;
import dev.mineagent.runtime.core.creature.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ModelSizeColorTest {
 private static String model(int extent,String color){return "{\"version\":1,\"boxes\":[{\"from\":[-50,0,-50],\"to\":[50,"+extent+",50],\"color\":"+color+"}],\"collision\":[-50,0,-50,50,"+extent+",50],\"texture\":\"textures/body.png\"}";}
 @Test void completeHundredBlockCubeAndTexture(){var m=RuntimeMesh.parse(model(100,"[12,34,56]"));assertEquals(100,m.collision().height());assertEquals(100,m.collision().width());assertEquals(0xff0c2238,m.triangles().getFirst().color());assertEquals("textures/body.png",m.texture());var c=CreatureDefinition.parse("{\"name\":\"Large\",\"model\":"+model(100,"[12,34,56,128]")+"}");assertEquals(0x800c2238,RuntimeMesh.parse(c.model()).triangles().getFirst().color());}
 @Test void overLimitAndInvalidRgbAreRejected(){assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(model(101,"[1,2,3]")));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(model(100,"[256,2,3]")));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(model(100,"[1.1,2,3]")));}
 @Test void combinedRigCannotBypassExtent(){var source="{\"name\":\"Rig\",\"bones\":[{\"name\":\"a\",\"pivot\":[0,0,0],\"model\":"+model(100,"[1,2,3]")+"},{\"name\":\"b\",\"parent\":\"a\",\"pivot\":[1,0,0],\"model\":"+model(100,"[1,2,3]")+"}]}";assertThrows(IllegalArgumentException.class,()->CreatureDefinition.parse(source));}
}
