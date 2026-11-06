package dev.mineagent.runtime.core.geometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GeometryDiagnosticTest {
    private String source(String part){return "{\"origin\":[0,60,0],\"parts\":["+part+"]}";}
    @Test void unknownShapeFieldHasAnExactPath(){
        var error=assertThrows(GeometryDiagnostic.class,()->WorldGeometry.parse(source("{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[3,3,3],\"holes\":[],\"material\":\"minecraft:stone\"}")));
        assertEquals("GEOMETRY_FIELDS",error.getMessage());assertEquals("/parts/0/holes",error.details().get("field"));assertTrue(error.details().containsKey("expected"));
    }
    @Test void pointAndCoordinateFailuresIdentifyTheirElement(){
        var error=assertThrows(GeometryDiagnostic.class,()->WorldGeometry.parse(source("{\"kind\":\"line\",\"points\":[[0,0,0],[1,2]],\"material\":\"minecraft:stone\"}")));
        assertEquals("/parts/0/points/1",error.details().get("field"));
        error=assertThrows(GeometryDiagnostic.class,()->WorldGeometry.parse(source("{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[1,\"bad\",1],\"material\":\"minecraft:stone\"}")));
        assertEquals("/parts/0/max/1",error.details().get("field"));
    }
    @Test void fractionalCurvedShellsRemainValidAndSyntaxHasLineInformation(){
        assertFalse(WorldGeometry.parse(source("{\"kind\":\"ellipsoid\",\"center\":[0,0,0],\"radius\":[2,2,2],\"thickness\":0.75,\"material\":\"minecraft:stone\"}")).cells().isEmpty());
        var error=assertThrows(GeometryDiagnostic.class,()->WorldGeometry.parse("{\n\"origin\": [0,60,0],\n\"parts\": [}"));assertTrue(((Number)error.details().get("line")).intValue()>=3);
    }
}
