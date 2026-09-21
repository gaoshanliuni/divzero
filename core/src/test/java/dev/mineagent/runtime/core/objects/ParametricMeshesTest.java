package dev.mineagent.runtime.core.objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ParametricMeshesTest {
    static final String SPHERE="{\"type\":\"sphere\",\"center\":[0,0.21,0],\"radius\":0.2,\"segments\":64,\"rings\":32,\"color\":\"#ee8800\"}";
    static String source(String primitives){return "{\"version\":2,\"primitives\":["+primitives+"],\"collision\":[-0.21,0,-0.21,0.21,0.42,0.21]}";}
    @Test void denseSphereHasExactRadiusSmoothNormalsOutwardFacesAndSmallSurfaceInset(){
        var m=RuntimeMesh.parse(source(SPHERE));assertEquals(2017,m.vertices().size());assertEquals(3968,m.triangles().size());
        for(var v:m.vertices()){double x=v.x(),y=v.y()-.21,z=v.z();assertEquals(.2,Math.sqrt(x*x+y*y+z*z),1e-7);assertTrue(v.hasNormal());assertEquals(1,Math.sqrt(v.nx()*v.nx()+v.ny()*v.ny()+v.nz()*v.nz()),1e-6);assertEquals(x/.2,v.nx(),1e-6);assertEquals(y/.2,v.ny(),1e-6);}
        var q=ModelGeometryTools.sphereQuality(m.vertices(),m.triangles(),0,.21,0,.2);assertTrue((double)q.get("maxFaceInsetRelativeToRadius")<.003);assertTrue((double)q.get("maxVertexRadiusError")<1e-7);
        var edges=new HashMap<String,Integer>();for(var t:m.triangles()){int[] ids={t.a(),t.b(),t.c()};for(int i=0;i<3;i++){String a=position(m.vertices().get(ids[i])),b=position(m.vertices().get(ids[(i+1)%3]));String key=a.compareTo(b)<0?a+"|"+b:b+"|"+a;edges.merge(key,1,Integer::sum);}}assertTrue(edges.values().stream().allMatch(n->n==2));
    }
    private static String position(RuntimeMesh.Vertex v){return Math.round(v.x()*1e7)+","+Math.round(v.y()*1e7)+","+Math.round(v.z()*1e7);}
    @Test void torusIsSmoothClosedAndOutwardOnEveryAxis(){
        for(String axis:List.of("x","y","z")){
            String p="{\"type\":\"torus\",\"center\":[0,0.21,0],\"majorRadius\":0.2005,\"minorRadius\":0.002,\"axis\":\""+axis+"\",\"color\":\"#222222\"}";
            var m=RuntimeMesh.parse(source(p));assertEquals(585,m.vertices().size());assertEquals(1024,m.triangles().size());
            for(var t:m.triangles()){var a=m.vertices().get(t.a());var b=m.vertices().get(t.b());var c=m.vertices().get(t.c());double x=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),y=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),z=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());assertTrue(x*(a.nx()+b.nx()+c.nx())+y*(a.ny()+b.ny()+c.ny())+z*(a.nz()+b.nz()+c.nz())>0);}
        }
    }
    @Test void compactBallAndThreeDecorativeRingsFitRealItemLimits(){var rings=new ArrayList<String>();for(String a:List.of("x","y","z"))rings.add("{\"type\":\"torus\",\"center\":[0,0.21,0],\"majorRadius\":0.2005,\"minorRadius\":0.002,\"axis\":\""+a+"\",\"color\":\"#222222\"}");String json=source(SPHERE+","+String.join(",",rings));assertTrue(json.length()<8192);var item=RuntimeItemBinding.create(UUID.randomUUID(),UUID.randomUUID(),"test","0".repeat(64),"model.json",json);assertEquals(3772,item.mesh().vertices().size());assertEquals(7040,item.mesh().triangles().size());assertEquals(item,RuntimeItemBinding.parse(item.encode()));}
    @Test void budgetsMalformedParametersAndLegacyVersionAreEnforcedBeforeExpansion(){assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE+","+SPHERE+","+SPHERE)));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE.replace("64","1000000000"))));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE.replace("0.2,","-0.2,"))));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE).replace("\"version\":2","\"version\":1")));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE.replace("\"sphere\"","\"execute\""))));}
    @Test void inspectionIsReadOnlyAndChecksItemBoundsAndStrictJson(){var report=ModelGeometryTools.inspect(source(SPHERE),"item");assertEquals("GEOMETRY_VALIDATED_NOT_EXECUTED",report.get("status"));assertEquals(2017,report.get("expandedVertices"));assertEquals(true,report.get("collisionContainsGeometry"));assertEquals(false,ModelGeometryTools.inspect(source(SPHERE.replace("0.21","0.5")),"item").get("collisionContainsGeometry"));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE)+"{}"));assertThrows(IllegalArgumentException.class,()->RuntimeMesh.parse(source(SPHERE).replace("\"version\":2","\"version\":4294967298")));assertThrows(IllegalArgumentException.class,()->ModelGeometryTools.inspect(source(SPHERE.replace("0.21","2.0")),"item"));}
    @Test void legacyFlatGeometryAndVersionTwoExplicitNormalsRemainAvailable(){String box="{\"version\":1,\"boxes\":[{\"from\":[-0.1,0,-0.1],\"to\":[0.1,0.2,0.1],\"color\":\"#ffffff\"}],\"collision\":[-0.1,0,-0.1,0.1,0.2,0.1]}";var m=RuntimeMesh.parse(box);assertEquals(12,m.triangles().size());assertTrue(m.vertices().stream().noneMatch(RuntimeMesh.Vertex::hasNormal));assertSame(m,RuntimeMesh.parse(box));var explicit=RuntimeMesh.parse("{\"version\":2,\"vertices\":[[-0.1,0,-0.1,0,0,0,0.5,0],[-0.1,0,0.1,0,1,0,0.5,0],[0.1,0,-0.1,1,0,0,0.5,0]],\"triangles\":[[0,1,2,\"#ffffff\"]],\"collision\":[-0.1,0,-0.1,0.1,0.1,0.1]}");assertTrue(explicit.vertices().stream().allMatch(v->v.hasNormal()&&v.ny()==1));}
}
