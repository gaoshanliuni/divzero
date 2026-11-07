package dev.mineagent.runtime.scripting.studio;

import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.compile.NativeCoderContext;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.scripting.packagehost.RuntimePackageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Persistence/consent fixtures only; not a Provider or a Native publication acceptance. */
class StudioCoderDependencyJournalTest {
    @TempDir Path temporary;
    final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),root=UUID.randomUUID(),dependency=UUID.randomUUID();
    private ScriptDependencySources sources(ContentAddressedStore store)throws Exception {
        String body="({read:function(){return 1;}});";var ref=CodeDraftSources.store(store,body);
        var node=new ScriptDependencyGraph.Node(dependency,"1.0.0",1,"a".repeat(64),UUID.randomUUID(),3,dev.mineagent.runtime.api.packages.ResourceIds.create(),"main.js",Map.of(),Map.of("main.js",ref));
        return new ScriptDependencySources(new ScriptDependencyGraph(1,root,Map.of(dependency,"1.0.0"),List.of(node)),Map.of(dependency,Map.of("main.js",ref)));
    }
    private StudioCoderJournal.Input input(ScriptDependencySources sources,boolean consent)throws Exception{
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","mojang",25,"UNIT_TEST_NOT_NATIVE",Map.of());
        return new StudioCoderJournal.Input(UUID.randomUUID(),world,owner,UUID.randomUUID(),UUID.randomUUID(),1,1,root,0,"",null,0,dev.mineagent.runtime.api.packages.ResourceIds.create(),0,"main.js","use the declared dependency",2,"",0,env,"",0,null,false,Map.of(),null,true,sources,consent);
    }
    @Test void requiresSourceDisclosureConsentAndPreservesSourcesThroughAdoptionAndReload()throws Exception {
        var store=new ContentAddressedStore(temporary.resolve("content"));var sources=sources(store);assertThrows(IllegalArgumentException.class,()->input(sources,false));
        var in=input(sources,true);var snapshot=sources.capture(store,()->true);UUID draftId;
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("runtime.db"),world,Clock.systemUTC(),packages)){
            var journal=service.coder();journal.begin(in);var job=journal.beginAttempt(in.operation());
            job=journal.bindScriptDependencySources(job.id(),job.last().id(),snapshot);
            String source="1 + 1;";var ref=CodeDraftSources.ref(source);
            var attempt=StudioCoderJournal.withScriptDependencySources(new StudioCoderJournal.Attempt(job.last().id(),1,"ACCEPTED","b".repeat(64),64,ref.sha256(),ref.bytes(),"offline-fixture","offline-fixture","offline-fixture","","",""),snapshot);
            job=journal.result(job.id(),attempt.id(),attempt,"READY","");assertNotEquals(ref.sha256(),attempt.candidateHash(in));
            journal.state(job.id(),job.revision(),"ADOPTING","");
            var draft=service.createIdempotent(journal.adoptionOperation(in),owner,in.task(),root,2,1,in.path(),source,Map.of(),false,in.dependencies(),in.dependencyDeclaration(),ref,attempt.candidateHash(in));draftId=draft.draftId();
            assertEquals(attempt.candidateHash(in),CodeDraftSources.fingerprint(draft));assertEquals(Map.of(dependency,"1.0.0"),draft.dependencies());journal.adopted(job.id(),draftId);
        }
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("runtime.db"),world,Clock.systemUTC(),packages)){
            var job=service.coder().get(in.operation());assertEquals("ADOPTED",job.state());assertEquals(sources,job.input().scriptDependencies());assertEquals(snapshot,job.last().scriptDependencySources());
            assertTrue(service.get(draftId).orElseThrow().dependencyDeclaration());assertEquals(Map.of(dependency,"1.0.0"),service.get(draftId).orElseThrow().dependencies());
        }
    }
    @Test void interruptedRequestRetainsItsPreparedContextWithoutReplay()throws Exception {
        var store=new ContentAddressedStore(temporary.resolve("content"));var sources=sources(store);var in=input(sources,true);var snapshot=sources.capture(store,()->true);
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("runtime.db"),world,Clock.systemUTC(),packages)){
            var journal=service.coder();journal.begin(in);var job=journal.beginAttempt(in.operation());journal.bindScriptDependencySources(job.id(),job.last().id(),snapshot);
        }
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("runtime.db"),world,Clock.systemUTC(),packages)){
            var job=service.coder().get(in.operation());assertEquals("INTERRUPTED",job.state());assertEquals("UNKNOWN",job.last().state());assertEquals(snapshot,job.last().scriptDependencySources());
            assertThrows(IllegalStateException.class,()->service.coder().beginAttempt(job.id()));
        }
    }

    @Test void selectedNativeContextRequiresConsentPersistsAndCannotBeChangedMidAttempt()throws Exception {
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"UNIT_TEST_NOT_NATIVE",Map.of());
        var overlay=new NativeCoderContext.Overlay("minecraft","net.minecraft.server.MinecraftServer","f".repeat(64),"f".repeat(64),"9".repeat(64),"FML_TRANSFORM_PIPELINE_PREDEFINE","process-epoch","TransformingClassLoader","tickServer","(Ljava/util/function/BooleanSupplier;)V","processor:test");var selection=new NativeCoderContext("c".repeat(64),env.fingerprint(),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(new NativeCoderContext.Type("minecraft","a".repeat(64),"net.minecraft.server.MinecraftServer")),List.of(),List.of(),List.of(overlay));
        assertThrows(IllegalArgumentException.class,()->new StudioCoderJournal.Input(UUID.randomUUID(),world,owner,UUID.randomUUID(),UUID.randomUUID(),1,1,root,0,"",null,0,dev.mineagent.runtime.api.packages.ResourceIds.create(),0,"Extension.java","use native api",1,"",0,env,"",0,null,false,Map.of(),null,false,null,false,selection,false));
        var in=new StudioCoderJournal.Input(UUID.randomUUID(),world,owner,UUID.randomUUID(),UUID.randomUUID(),1,1,root,0,"",null,0,dev.mineagent.runtime.api.packages.ResourceIds.create(),0,"Extension.java","use native api",1,"",0,env,"",0,null,false,Map.of(),null,false,null,false,selection,true);
        var reference=new NativeCoderContext.Snapshot(selection.fingerprint(),"d".repeat(64),512,"e".repeat(64));
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("native.db"),world,Clock.systemUTC(),packages)){
            var journal=service.coder();journal.begin(in);var job=journal.beginAttempt(in.operation());job=journal.bindNativeContext(job.id(),job.last().id(),reference);
            UUID jobId=job.id(),attemptId=job.last().id();assertThrows(IllegalStateException.class,()->journal.bindNativeContext(jobId,attemptId,new NativeCoderContext.Snapshot(selection.fingerprint(),"e".repeat(64),512)));
        }
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("native.db"),world,Clock.systemUTC(),packages)){
            var job=service.coder().get(in.operation());assertEquals("INTERRUPTED",job.state());assertEquals(reference,job.last().nativeContext());assertEquals(selection,job.input().nativeSelection());
        }
    }
    @Test void exactAdoptedNativeCandidateIsPublicationSourceUntilManualEdit()throws Exception {
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"UNIT_TEST_NOT_NATIVE",Map.of());var overlay=new NativeCoderContext.Overlay("minecraft","net.minecraft.server.MinecraftServer","f".repeat(64),"f".repeat(64),"9".repeat(64),"FML_TRANSFORM_PIPELINE_PREDEFINE","process-epoch","TransformingClassLoader","tickServer","(Ljava/util/function/BooleanSupplier;)V","processor:test");var selection=new NativeCoderContext("c".repeat(64),env.fingerprint(),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(),List.of(),List.of(),List.of(overlay));var in=new StudioCoderJournal.Input(UUID.randomUUID(),world,owner,UUID.randomUUID(),UUID.randomUUID(),1,1,root,0,"",null,0,dev.mineagent.runtime.api.packages.ResourceIds.create(),0,"Extension.java","use native api",1,"",0,env,"",0,null,false,Map.of(),null,false,null,false,selection,true);var context=new NativeCoderContext.Snapshot(selection.fingerprint(),"d".repeat(64),512,"e".repeat(64));String source="public final class Extension {}";var ref=CodeDraftSources.ref(source);
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(temporary.resolve("publication-source.db"),world,Clock.systemUTC(),packages)){var journal=service.coder();journal.begin(in);var job=journal.beginAttempt(in.operation());job=journal.bindNativeContext(job.id(),job.last().id(),context);var accepted=StudioCoderJournal.withNativeContext(new StudioCoderJournal.Attempt(job.last().id(),1,"ACCEPTED","b".repeat(64),64,ref.sha256(),ref.bytes(),"offline","offline","offline","","a".repeat(64),""),context);job=journal.result(job.id(),accepted.id(),accepted,"READY","");job=journal.state(job.id(),job.revision(),"ADOPTING","");var draft=service.createIdempotent(journal.adoptionOperation(in),owner,in.task(),root,2,1,in.path(),source,Map.of(),false,Map.of(),false,ref,accepted.candidateHash(in));journal.adopted(job.id(),draft.draftId());assertEquals(context,journal.nativePublicationSource(draft).orElseThrow().last().nativeContext());var changed=service.update(draft.draftId(),owner,draft.revision(),"public final class Extension { int changed; }").draft();assertTrue(journal.nativePublicationSource(changed).isEmpty());}
    }
}
