package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.ResourceIds;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class DependencyReceiptTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private JavaDependencyGraph.Node node(UUID id,String artifact,String source,String nativeId){return new JavaDependencyGraph.Node(id,"1",1,ResourceIds.create(),UUID.randomUUID(),1,artifact,source,"fixture.Extension",nativeId,Map.of());}
    private byte[] artifact(String source,String nativeId)throws Exception{
        var output=new java.io.ByteArrayOutputStream();
        try(var jar=new JarOutputStream(output)){
            jar.putNextEntry(new JarEntry("fixture/Extension.class"));jar.write(java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("fixture.Extension"),b->b.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC)));jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/mineagent/compilation.json"));jar.write(json.writeValueAsBytes(Map.of("className","fixture.Extension","sourceSha256",source,"nativeClasspath",nativeId,"javaDependencies","")));jar.closeEntry();
        }
        return output.toByteArray();
    }
    @Test void comparesVersionMetadataAcrossSerializationAndRejectsChangedClosure()throws Exception{
        UUID pkg=UUID.randomUUID(),owner=UUID.randomUUID();var node=node(pkg,ResourceIds.create(),ResourceIds.create(),ResourceIds.create());var graph=new JavaDependencyGraph(1,owner,Map.of(pkg,"1"),List.of(node));
        assertTrue(graph.receiptHash().startsWith("dependencies-v2:"));assertEquals(64,DependencyReceipt.observationId(graph.fingerprint()).length());assertEquals(DependencyReceipt.observationId(graph.fingerprint()),DependencyReceipt.observationId(graph.fingerprint()));assertEquals(graph.fingerprint(),json.readValue(json.writeValueAsBytes(graph),JavaDependencyGraph.class).fingerprint());
        var changed=new JavaDependencyGraph.Node(pkg,"1",2,node.canonical(),node.publication(),1,node.artifact(),node.sourceHash(),node.className(),node.nativeClasspath(),Map.of());
        assertNotEquals(graph.fingerprint(),new JavaDependencyGraph(1,owner,Map.of(pkg,"1"),List.of(changed)).fingerprint());
        assertTrue(DependencyReceipt.readable("a".repeat(64)));assertNotEquals("a".repeat(64),graph.fingerprint());assertFalse(DependencyReceipt.readable("not-a-receipt"));
    }
    @Test void materializedDependenciesAreIndependentAndCheckedAgainstCapturedBytes()throws Exception{
        var store=new ContentAddressedStore(root.resolve("objects"));String source=ResourceIds.create(),nativeId=ResourceIds.create();byte[] bytes=artifact(source,nativeId);var saved=store.put(bytes);UUID pkg=UUID.randomUUID();var graph=new JavaDependencyGraph(1,UUID.randomUUID(),Map.of(pkg,"1"),List.of(node(pkg,saved.sha256(),source,nativeId)));
        var prepared=JavaDependencyArtifacts.materialize(graph,store,root.resolve("compile"));prepared.verify();assertFalse(Files.isSameFile(prepared.paths().getFirst(),saved.path()));
        byte[] changed=bytes.clone();changed[changed.length-1]^=1;Files.write(prepared.paths().getFirst(),changed);
        assertThrows(IllegalStateException.class,prepared::verify);assertArrayEquals(bytes,store.read(saved.sha256()));
        var observed=JavaDependencyArtifacts.inspect(graph,store);Files.write(saved.path(),changed);assertThrows(IllegalStateException.class,observed::verify);
    }
    @Test void rejectsArtifactFromAnotherSourceVersion()throws Exception{
        var store=new ContentAddressedStore(root.resolve("objects"));String source=ResourceIds.create(),nativeId=ResourceIds.create();var saved=store.put(artifact(source,nativeId));UUID pkg=UUID.randomUUID();var graph=new JavaDependencyGraph(1,UUID.randomUUID(),Map.of(pkg,"1"),List.of(node(pkg,saved.sha256(),ResourceIds.create(),nativeId)));
        assertThrows(IllegalStateException.class,()->JavaDependencyArtifacts.inspect(graph,store));
    }
}
