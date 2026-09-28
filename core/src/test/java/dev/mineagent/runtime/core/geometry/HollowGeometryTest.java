package dev.mineagent.runtime.core.geometry;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HollowGeometryTest {
    private static String source(String part) {
        return "{\"origin\":[0,0,0],\"parts\":[{\"material\":\"minecraft:stone\"," + part + "}]}";
    }
    private static final String OUTER="\"kind\":\"polygon\",\"points\":[[0,0,0],[12,0,0],[12,0,12],[0,0,12]],\"height\":7";
    private static final String HOLE=",\"holes\":[[[4,0,4],[8,0,4],[8,0,8],[4,0,8]]]";
    private static Set<WorldGeometry.Pos> positions(String part) {
        var out=new LinkedHashSet<WorldGeometry.Pos>();
        WorldGeometry.stream(source(part), c->{assertNotEquals("minecraft:air",c.state());assertTrue(out.add(c.pos()),"duplicate emission");});
        return out;
    }
    private static WorldGeometry.Pos p(int x,int y,int z){return new WorldGeometry.Pos(x,y,z);}

    @Test void boundarySpansExactlyMatchLegacyOrderIncludingDegenerateAndOverlappingThickness() {
        var random=new Random(5721);
        for(int trial=0;trial<200;trial++) {
            int nx=random.nextInt(1,12),ny=random.nextInt(1,12),nz=random.nextInt(1,12),t=random.nextInt(1,15);
            for(String mode:List.of("solid","walls","shell")) {
                var expected=new ArrayList<WorldGeometry.Pos>();
                for(int x=0;x<nx;x++)for(int y=0;y<ny;y++)for(int z=0;z<nz;z++)
                    if(mode.equals("solid")||x<t||nx-1-x<t||z<t||nz-1-z<t||mode.equals("shell")&&(y<t||ny-1-y<t))expected.add(p(x-3,y-2,z-4));
                var actual=positions("\"kind\":\"box\",\"min\":[-3,-2,-4],\"max\":["+(nx-4)+","+(ny-3)+","+(nz-5)+"],\"mode\":\""+mode+"\",\"thickness\":"+t);
                assertEquals(expected,new ArrayList<>(actual));
            }
        }
    }
    @Test void courtyardWallsHaveThicknessAndDoNotClearTheInterior() {
        var cells=positions(OUTER+HOLE+",\"mode\":\"walls\",\"thickness\":2");
        assertTrue(cells.contains(p(1,3,6)));
        assertFalse(cells.contains(p(2,3,2)));
        assertTrue(cells.contains(p(3,3,6)));
        assertTrue(cells.contains(p(4,3,6)));
        assertFalse(cells.contains(p(6,3,6)));
        assertFalse(cells.contains(p(2,0,2)));
    }
    @Test void independentCapsLeaveCourtyardOpenAndDoNotDoubleEmit() {
        var shell=positions(OUTER+HOLE+",\"mode\":\"shell\"");
        assertTrue(shell.contains(p(2,0,2)));
        assertTrue(shell.contains(p(2,6,2)));
        assertFalse(shell.contains(p(2,3,2)));
        assertFalse(shell.contains(p(6,0,6)));
        assertFalse(shell.contains(p(6,6,6)));
        var roofOnly=positions(OUTER+",\"mode\":\"walls\",\"cap_top\":true");
        assertFalse(roofOnly.contains(p(6,0,6)));
        assertTrue(roofOnly.contains(p(6,6,6)));
        positions(OUTER.replace("\"height\":7","\"height\":1")+",\"mode\":\"shell\",\"thickness\":2");
    }
    @Test void concaveWallDoesNotFillNotchAndWindingDoesNotChangeResult() {
        String shape="\"kind\":\"polygon\",\"height\":4,\"mode\":\"walls\",\"points\":";
        var a=positions(shape+"[[0,0,0],[6,0,0],[6,0,2],[2,0,2],[2,0,6],[0,0,6]]");
        var b=positions(shape+"[[0,0,6],[2,0,6],[2,0,2],[6,0,2],[6,0,0],[0,0,0]]");
        assertEquals(a,b);assertFalse(a.contains(p(4,1,4)));assertTrue(a.contains(p(2,1,4)));assertFalse(a.contains(p(1,1,1)));
    }
    @Test void malformedRingsAndHolesRejectBeforeAnyEmission() {
        for(String extra:List.of(
                ",\"holes\":[[[0,0,2],[2,0,2],[2,0,4],[0,0,4]]]",
                ",\"holes\":[[[13,0,2],[15,0,2],[15,0,4]]]",
                ",\"holes\":[[[4,0,4],[8,0,4],[8,0,8],[4,0,8]],[[5,0,5],[7,0,5],[7,0,7],[5,0,7]]]",
                ",\"holes\":[[[4,0,4],[8,1,4],[8,0,8]]]",
                ",\"cap_top\":\"true\"",
                ",\"thickness\":0")) {
            var emitted=new ArrayList<WorldGeometry.Cell>();
            assertThrows(IllegalArgumentException.class,()->WorldGeometry.stream(source(OUTER+extra),emitted::add));
            assertTrue(emitted.isEmpty());
        }
        assertThrows(IllegalArgumentException.class,()->positions("\"kind\":\"polygon\",\"height\":1,\"points\":[[0,0,0],[4,0,0],[2,0,0],[2,0,4],[0,0,4]]"));
    }
    @Test void hollowGeometryStillUsesNormalMaterialAndOrientationPipeline() {
        String part=OUTER+",\"mode\":\"walls\",\"transforms\":[{\"op\":\"rotate\",\"turns\":1},{\"op\":\"mirror\",\"axis\":\"x\"}]";
        WorldGeometry.stream(source(part),c->assertEquals(List.of("r1","mx"),c.orientation()));
    }
}
