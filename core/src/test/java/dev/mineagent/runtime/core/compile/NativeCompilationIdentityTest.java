package dev.mineagent.runtime.core.compile;
import dev.mineagent.runtime.core.content.ContentAddressedStore;import dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class NativeCompilationIdentityTest {
    @TempDir Path root;
    @Test void checksDuplicateClassesAgainstOriginalSourceWithoutRetainingEveryByteArray()throws Exception{var index=new ClassSourceIndex();var reads=new AtomicInteger();byte[] first={1,2,3};assertTrue(index.observe("A.class",first,()->{reads.incrementAndGet();return first;}));assertTrue(index.observe("B.class",new byte[]{7},()->new byte[]{7}));assertEquals(0,reads.get());assertFalse(index.observe("A.class",first,()->first));assertEquals(1,reads.get());assertThrows(IllegalStateException.class,()->index.observe("A.class",new byte[]{1,2,4},()->first));}
    @Test void preservesAssignedSnapshotIdsAndComparesMaterializedCopiesToOriginals()throws Exception{
        var output=new java.io.ByteArrayOutputStream();long classBytes=0;
        try(var jar=new java.util.jar.JarOutputStream(output)){for(String name:List.of("net.minecraft.server.MinecraftServer","net.neoforged.neoforge.common.NeoForge","dev.mineagent.runtime.api.packages.RuntimePackage")){byte[] bytes=java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of(name),builder->builder.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC));classBytes+=bytes.length;jar.putNextEntry(new java.util.jar.JarEntry(name.replace('.','/')+".class"));jar.write(bytes);jar.closeEntry();}}
        var store=new ContentAddressedStore(root.resolve("content"));var module=store.put(output.toByteArray());var environment=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"FIXTURE",Map.of());
        var snapshot=new NativeCompilationSnapshot(1,"official","SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",environment,List.of(new NativeCompilationSnapshot.Module("fixture","1",module.sha256(),module.size(),3)),classBytes,3);
        var reference=store.put(snapshot.encode());var restored=NativeCompilationSnapshot.read(store,reference.sha256());assertEquals(snapshot,restored);var paths=restored.materialize(store,root.resolve("classes"));restored.verifyMaterialized(paths);assertFalse(Files.isSameFile(paths.getFirst(),module.path()));
        byte[] changed=Files.readAllBytes(paths.getFirst());changed[changed.length-1]^=1;Files.write(paths.getFirst(),changed);assertThrows(IllegalStateException.class,()->restored.verifyMaterialized(paths));assertArrayEquals(output.toByteArray(),store.read(module.sha256()));
    }
}
