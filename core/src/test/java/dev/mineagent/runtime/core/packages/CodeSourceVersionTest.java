package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CodeSourceVersionTest {
    @TempDir Path root;
    @Test void primaryAndSourceSetSurviveSerializationWithoutRecomputingContentIdentity()throws Exception{
        var content=new ContentAddressedStore(root);var helper=CodeDraftSources.store(content,"exports.n=2;");
        var draft=new CodeDraft(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1,"main.js","require('./helper.js').n;",CodeDraftStatus.DRAFT,List.of(),1,Map.of("helper.js",helper),List.of(),true);
        CodeDraftSources.bind(draft,content);var json=new com.fasterxml.jackson.databind.ObjectMapper();var restored=json.readValue(json.writeValueAsString(draft),CodeDraft.class);
        assertEquals(draft,restored);assertEquals(draft.source(),CodeDraftSources.read(content,restored.primarySource()));assertEquals(CodeDraftSources.set(draft),CodeDraftSources.readSet(content,restored.sourceVersion()));
        var changed=new TreeMap<>(CodeDraftSources.refs(draft));changed.put("helper.js",CodeDraftSources.store(content,"exports.n=3;"));
        assertThrows(IllegalStateException.class,()->CodeDraftSources.requireSet(CodeDraftSources.set(draft),draft.path(),changed,Map.of()));
        assertThrows(dev.mineagent.runtime.core.content.ContentIntegrityException.class,()->CodeDraftSources.storeSet(content,new CodeDraftSources.SourceSet(draft.sourceVersion(),draft.path(),changed,Map.of())));
        Files.writeString(content.pathFor(helper.sha256()),"exports.n=4;");assertThrows(IllegalStateException.class,()->CodeDraftSources.read(content,helper));
    }
    @Test void legacyDraftIdentityIsDerivedFromItsObjectVersionAndNeverChangesOnRead()throws Exception{
        var draft=new CodeDraft(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,7,"main.js","1;",CodeDraftStatus.DRAFT,List.of(),1);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var old=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(draft);old.remove(List.of("primarySource","sourceVersion"));
        var first=json.treeToValue(old,CodeDraft.class);var second=json.treeToValue(old,CodeDraft.class);assertEquals(first.primarySource(),second.primarySource());assertEquals(first.sourceVersion(),second.sourceVersion());assertNotEquals(first.primarySource().sha256(),first.sourceVersion());
        var content=new ContentAddressedStore(root);CodeDraftSources.bind(first,content);CodeDraftSources.bind(second,content);assertEquals("1;",CodeDraftSources.read(content,first.primarySource()));
    }
}
