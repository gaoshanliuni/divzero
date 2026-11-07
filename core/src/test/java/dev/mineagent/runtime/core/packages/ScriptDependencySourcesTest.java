package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptDependencySourcesTest {
    @TempDir Path temporary;
    private final UUID root=UUID.randomUUID(),dependency=UUID.randomUUID();
    private ScriptDependencySources context(Map<String,String> text,ContentAddressedStore store)throws Exception {
        var refs=new TreeMap<String,CodeDraft.SourceRef>();for(var file:text.entrySet()){refs.put(file.getKey(),CodeDraftSources.store(store,file.getValue()));}
        var node=new ScriptDependencyGraph.Node(dependency,"1.0.0",1,"a".repeat(64),UUID.randomUUID(),3,dev.mineagent.runtime.api.packages.ResourceIds.create(),"main.js",Map.of(),refs);
        return new ScriptDependencySources(new ScriptDependencyGraph(1,root,Map.of(dependency,"1.0.0"),List.of(node)),Map.of(dependency,refs));
    }
    @Test void capturesEveryVerifiedFileAsDataAndSurvivesRecordSerialization()throws Exception {
        var store=new ContentAddressedStore(temporary);var source=context(Map.of("main.js","throw new Error('must not evaluate');","helper.js","({privateImplementation: 7});"),store);
        var snapshot=source.capture(store,()->true);String json=source.read(snapshot,store);
        assertTrue(json.contains("must not evaluate"));assertTrue(json.contains("privateImplementation"));assertEquals(source.graph().fingerprint(),new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("graphHash").asText());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();assertEquals(source,mapper.readValue(mapper.writeValueAsBytes(source),ScriptDependencySources.class));
        assertEquals(json,new String(store.read(snapshot.sha256()),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void rejectsMissingFilesChangedSourceAndRevokedPreparation()throws Exception {
        var store=new ContentAddressedStore(temporary);var source=context(Map.of("main.js","1;"),store);
        assertThrows(IllegalArgumentException.class,()->new ScriptDependencySources(source.graph(),Map.of()));
        assertThrows(IllegalStateException.class,()->source.capture(store,()->false));
        var ref=source.files().get(dependency).get("main.js");Files.writeString(store.pathFor(ref.sha256()),"2;");
        assertThrows(IllegalStateException.class,()->source.capture(store,()->true));
    }
    @Test void checksEncodedContextSizeRatherThanSilentlyDroppingFiles()throws Exception {
        var store=new ContentAddressedStore(temporary);var files=new TreeMap<String,String>();String escaped="\t".repeat(16000);
        files.put("main.js",escaped);for(int i=1;i<30;i++)files.put("part"+i+".js",escaped);
        var source=context(files,store);var error=assertThrows(IllegalStateException.class,()->source.capture(store,()->true));
        assertEquals("STUDIO_CODER_SCRIPT_SOURCE_CONTEXT_LIMIT",error.getMessage());
    }
    @Test void rejectsChangedSnapshotOrWrongGraph()throws Exception {
        var store=new ContentAddressedStore(temporary);var source=context(Map.of("main.js","1;"),store);var snapshot=source.capture(store,()->true);
        var wrong=new ScriptDependencySources.Snapshot("b".repeat(64),snapshot.sha256(),snapshot.bytes());
        assertThrows(IllegalStateException.class,()->source.read(wrong,store));
        Files.writeString(store.pathFor(snapshot.sha256()),"{}");assertThrows(IllegalStateException.class,()->source.read(snapshot,store));
    }
}
