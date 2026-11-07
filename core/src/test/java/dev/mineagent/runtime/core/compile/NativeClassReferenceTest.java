package dev.mineagent.runtime.core.compile;

import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeClassReferenceTest {
    @TempDir Path root;
    private byte[] type(int flags){return java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("fixture.Example"),b->b.withFlags(flags));}
    private NativeCompilationSnapshot snapshot(ContentAddressedStore store,byte[] bytes)throws Exception{
        var output=new java.io.ByteArrayOutputStream();try(var jar=new JarOutputStream(output)){jar.putNextEntry(new JarEntry("fixture/Example.class"));jar.write(bytes);jar.closeEntry();}
        var resource=store.put(output.toByteArray());var environment=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"FIXTURE",Map.of());
        return new NativeCompilationSnapshot(1,"official","SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",environment,List.of(new NativeCompilationSnapshot.Module("fixture","1",resource.sha256(),resource.size(),1)),bytes.length,1);
    }
    @Test void reusesAClassReferenceOnlyWithinItsImmutableModuleAndReadsNativeContext()throws Exception{
        var store=new ContentAddressedStore(root);byte[] bytes=type(1);var snapshot=snapshot(store,bytes);String reference=snapshot.classReference(store,"fixture","fixture.Example");
        assertEquals(reference,snapshot.classReference(new ContentAddressedStore(root),"fixture","fixture.Example"));assertArrayEquals(bytes,store.read(reference));
        assertNotEquals(reference,snapshot(store,bytes).classReference(store,"fixture","fixture.Example"));
        String snapshotId=store.put(snapshot.encode()).sha256();var selection=NativeCoderContext.resolve(snapshot,snapshotId,snapshot.environment().fingerprint(),List.of(Map.of("module","fixture","class","fixture.Example")),()->true);
        var captured=selection.capture(snapshot,store,()->true);assertTrue(selection.read(captured,store).contains(reference));
    }
    @Test void overlaysCompareTheActualRawClassAndNeverMutateTheBase()throws Exception{
        var store=new ContentAddressedStore(root);byte[] raw=type(1),live=type(17);var snapshot=snapshot(store,raw);String rawRef=snapshot.classReference(store,"fixture","fixture.Example"),liveRef=store.put(live).sha256();
        var overlay=new NativeCoderContext.Overlay("fixture","fixture.Example",liveRef,liveRef,rawRef,"FML_TRANSFORM_PIPELINE_PREDEFINE","fixture-epoch","fixture-loader","","","fixture");
        var changed=NativeOverlaySnapshot.apply(snapshot,store,List.of(overlay),()->true);
        assertArrayEquals(live,changed.snapshot().classBytes(store,"fixture","fixture.Example"));assertArrayEquals(raw,snapshot.classBytes(store,"fixture","fixture.Example"));
        String differentRaw=store.put(live).sha256();var stale=new NativeCoderContext.Overlay("fixture","fixture.Example",liveRef,liveRef,differentRaw,"FML_TRANSFORM_PIPELINE_PREDEFINE","fixture-epoch","fixture-loader","","","fixture");
        assertThrows(IllegalStateException.class,()->NativeOverlaySnapshot.apply(snapshot,store,List.of(stale),()->true));
    }
}
