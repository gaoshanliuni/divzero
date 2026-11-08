package dev.mineagent.runtime.core.compile;

import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCoderContextTest {
    @TempDir Path temporary;
    public static final class Fixture {
        private static final String SECRET_BODY_LITERAL="must-not-appear-in-symbol-context";
        private static final String UNSELECTED_BODY_LITERAL="must-not-appear-in-selected-body-context";
        public int visible(String value)throws IOException{return value.length()+SECRET_BODY_LITERAL.length();}
        private String hidden(){return UNSELECTED_BODY_LITERAL;}
    }
    interface AbstractFixture {void missing();}
    private record Source(ContentAddressedStore store,NativeCompilationSnapshot snapshot,String snapshotHash,String moduleHash,String className){}
    private Source source()throws Exception {
        var store=new ContentAddressedStore(temporary);String name=Fixture.class.getName(),entry=name.replace('.','/')+".class";byte[] bytes;
        try(var in=Fixture.class.getClassLoader().getResourceAsStream(entry)){bytes=Objects.requireNonNull(in).readAllBytes();}
        var output=new ByteArrayOutputStream();try(var jar=new JarOutputStream(output)){var item=new JarEntry(entry);item.setTime(0);jar.putNextEntry(item);jar.write(bytes);jar.closeEntry();}
        var module=store.put(output.toByteArray());var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"TEST_ONLY",Map.of());
        var snapshot=new NativeCompilationSnapshot(1,"official","SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",env,List.of(new NativeCompilationSnapshot.Module("fixture.module","1",module.sha256(),module.size(),1)),bytes.length,1);
        var snapshotObject=store.put(snapshot.encode());return new Source(store,snapshot,snapshotObject.sha256(),module.sha256(),name);
    }
    @Test void capturesExactSelectedDeclarationsWithoutBodiesOrInitialization()throws Exception {
        var source=source();var selection=NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),source.snapshot().environment().fingerprint(),List.of(Map.of("module","fixture.module","class",source.className())),()->true);
        var reference=selection.capture(source.snapshot(),source.store(),()->true);String context=selection.read(reference,source.store());
        assertTrue(context.contains("visible"),context);assertTrue(context.contains("Ljava/lang/String;"),context);assertTrue(context.contains("java.io.IOException"),context);
        assertTrue(context.contains("hidden"));assertFalse(context.contains(Fixture.SECRET_BODY_LITERAL));assertEquals(selection.fingerprint(),reference.selectionHash());assertTrue(NativeKnowledgeArtifact.semanticHash(context).matches("[a-f0-9]{64}"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String encoded=mapper.writeValueAsString(selection);assertFalse(encoded.contains("methods"));assertFalse(encoded.contains("overlays"));assertEquals(selection,mapper.readValue(encoded,NativeCoderContext.class));
    }
    @Test void capturesOnlyExplicitlySelectedMethodImplementationWithExactDescriptor()throws Exception {
        var source=source();var types=List.of(Map.of("module","fixture.module","class",source.className()));var methods=List.of(Map.of("module","fixture.module","class",source.className(),"method","visible","descriptor","(Ljava/lang/String;)I"));
        var selection=NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),source.snapshot().environment().fingerprint(),types,methods,()->true);
        var reference=selection.capture(source.snapshot(),source.store(),()->true);String context=selection.read(reference,source.store());
        assertEquals(1,selection.methods().size());assertTrue(context.contains("METHOD_IMPLEMENTATIONS"),context);assertTrue(context.contains(Fixture.SECRET_BODY_LITERAL),context);assertFalse(context.contains(Fixture.UNSELECTED_BODY_LITERAL),context);assertTrue(context.contains("java/lang/String"),context);assertTrue(context.contains("length"),context);
        var wrong=List.of(Map.of("module","fixture.module","class",source.className(),"method","visible","descriptor","()V"));assertThrows(IllegalArgumentException.class,()->NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),source.snapshot().environment().fingerprint(),types,wrong,()->true).capture(source.snapshot(),source.store(),()->true));
    }
    @Test void reportsAbstractMethodAsExactNoCodeInsteadOfInventingImplementation()throws Exception{
        String entry=AbstractFixture.class.getName().replace('.','/')+".class";byte[] bytes;try(var in=AbstractFixture.class.getClassLoader().getResourceAsStream(entry)){bytes=Objects.requireNonNull(in).readAllBytes();}var body=ClassFileMethodBody.inspectAll(bytes,"missing","()V");assertEquals("ABSTRACT_OR_NATIVE",body.implementationKind());assertEquals(0,body.total());assertTrue(body.instructions().isEmpty());
    }
    @Test void mergesOnlySelectionsFromOneExactSnapshotAndDeduplicatesTypes(){
        var first=new NativeCoderContext("a".repeat(64),"b".repeat(64),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(new NativeCoderContext.Type("minecraft","c".repeat(64),Fixture.class.getName())),List.of(new NativeCoderContext.Method("minecraft",Fixture.class.getName(),"visible","(Ljava/lang/String;)I")));
        var second=new NativeCoderContext(first.snapshot(),first.environment(),first.physicalSide(),first.mappingStatus(),first.types());var merged=NativeCoderContext.merge(List.of(first,second));assertEquals(1,merged.types().size());assertEquals(1,merged.methods().size());
        var stale=new NativeCoderContext("d".repeat(64),first.environment(),first.physicalSide(),first.mappingStatus(),first.types());assertThrows(IllegalArgumentException.class,()->NativeCoderContext.merge(List.of(first,stale)));
    }
    @Test void rawAndOverlaySelectionsShareOneSixteenItemLimit(){
        var types=new ArrayList<NativeCoderContext.Type>();for(int i=0;i<15;i++)types.add(new NativeCoderContext.Type("fixture.module","c".repeat(64),"dev.example.Raw"+i));var one=new NativeCoderContext.Overlay("fixture.module","dev.example.Live1","d".repeat(64),"d".repeat(64),"","JVM_RETRANSFORM_LIVE_DEFINITION","epoch","loader","","","audit");var two=new NativeCoderContext.Overlay("fixture.module","dev.example.Live2","e".repeat(64),"e".repeat(64),"","JVM_RETRANSFORM_LIVE_DEFINITION","epoch","loader","","","audit");
        assertDoesNotThrow(()->new NativeCoderContext("a".repeat(64),"b".repeat(64),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",types,List.of(),List.of(),List.of(one)));assertThrows(IllegalArgumentException.class,()->new NativeCoderContext("a".repeat(64),"b".repeat(64),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",types,List.of(),List.of(),List.of(one,two)));
    }
    @Test void capturesExplicitLiveOverlayAndBuildsFrozenCompilationSnapshot()throws Exception{
        var source=source();byte[] bytes=source.snapshot().classBytes(source.store(),"fixture.module",source.className());var live=source.store().put(bytes);var overlay=new NativeCoderContext.Overlay("fixture.module",source.className(),live.sha256(),live.sha256(),source.snapshot().classReference(source.store(),"fixture.module",source.className()),"FML_TRANSFORM_PIPELINE_PREDEFINE","epoch","TransformingClassLoader","visible","(Ljava/lang/String;)I","processor:a");
        var selection=new NativeCoderContext(source.snapshotHash(),source.snapshot().environment().fingerprint(),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(),List.of(),List.of(),List.of(overlay));var reference=selection.capture(source.snapshot(),source.store(),()->true);assertTrue(reference.compilationSnapshot().matches("[a-f0-9]{64}"));String context=selection.read(reference,source.store());assertTrue(context.contains("liveClasses"),context);assertTrue(context.contains("FML_TRANSFORM_PIPELINE_PREDEFINE"),context);var derived=NativeCompilationSnapshot.read(source.store(),reference.compilationSnapshot());assertEquals("RAW_MODULE_RESOURCES_WITH_EXPLICIT_LIVE_OVERLAYS",derived.mappingStatus());assertArrayEquals(bytes,derived.classBytes(source.store(),"fixture.module",source.className()));
    }
    @Test void rejectsWrongSnapshotModuleClassHashAndDuplicateSelection()throws Exception {
        var source=source();var request=List.of(Map.of("module","fixture.module","class",source.className()));
        assertThrows(IllegalArgumentException.class,()->NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),"a".repeat(64),request,()->true));
        assertThrows(IllegalArgumentException.class,()->NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),source.snapshot().environment().fingerprint(),List.of(Map.of("module","missing","class",source.className())),()->true));
        var selection=NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),source.snapshot().environment().fingerprint(),request,()->true);
        assertThrows(IllegalArgumentException.class,()->new NativeCoderContext(selection.snapshot(),selection.environment(),selection.physicalSide(),selection.mappingStatus(),List.of(selection.types().getFirst(),selection.types().getFirst())));
        var changed=new NativeCoderContext.Type("fixture.module","b".repeat(64),source.className());
        assertThrows(IllegalStateException.class,()->new NativeCoderContext(selection.snapshot(),selection.environment(),selection.physicalSide(),selection.mappingStatus(),List.of(changed)).capture(source.snapshot(),source.store(),()->true));
    }
    @Test void contextReferenceDetectsTamperingAndRevokedPreparation()throws Exception {
        var source=source();var selection=NativeCoderContext.resolve(source.snapshot(),source.snapshotHash(),source.snapshot().environment().fingerprint(),List.of(Map.of("module","fixture.module","class",source.className())),()->true);
        assertThrows(IllegalStateException.class,()->selection.capture(source.snapshot(),source.store(),()->false));var reference=selection.capture(source.snapshot(),source.store(),()->true);
        Files.writeString(source.store().pathFor(reference.sha256()),"{}");assertThrows(IllegalStateException.class,()->selection.read(reference,source.store()));
    }
}
