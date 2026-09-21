package dev.mineagent.runtime.core.objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeModelBundleTest {
    private static final byte[] MODEL="{\"version\":1,\"boxes\":[{\"from\":[-1,0,-1],\"to\":[1,2,1],\"color\":\"#cc8833\"}],\"collision\":[-1,0,-1,1,2,1]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Test void versionTwoParametricSourcePreservesHashAndNormalsAcrossTheWire(){var source=ParametricMeshesTest.source(ParametricMeshesTest.SPHERE).getBytes(java.nio.charset.StandardCharsets.UTF_8);var bundle=RuntimeModelBundle.create(source,new byte[0]);var received=RuntimeModelBundle.decode(bundle.bytes(),bundle.sha256());assertEquals(bundle.sha256(),received.sha256());assertEquals(bundle.mesh(),received.mesh());assertTrue(received.mesh().vertices().stream().allMatch(RuntimeMesh.Vertex::hasNormal));}
    @Test void bundleHasAStableIdentityAndVerifiesBytesBeforeParsing(){
        var bundle=RuntimeModelBundle.create(MODEL,new byte[0]);assertEquals(bundle.sha256(),RuntimeModelBundle.create(MODEL,new byte[0]).sha256());assertEquals(12,RuntimeModelBundle.decode(bundle.bytes(),bundle.sha256()).mesh().triangles().size());
        var bytes=bundle.bytes();bytes[bytes.length-2]^=1;assertThrows(IllegalArgumentException.class,()->RuntimeModelBundle.decode(bytes,bundle.sha256()));
        assertThrows(IllegalArgumentException.class,()->RuntimeModelBundle.create(MODEL,new byte[]{1,2,3}));
    }
}
