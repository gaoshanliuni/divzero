package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeKnowledgeIdentityTest {
    @TempDir Path root;
    private NativeCoderContext selection(){return new NativeCoderContext("a".repeat(64),"b".repeat(64),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(new NativeCoderContext.Type("fixture","c".repeat(64),"example.Type")));}
    @Test void selectionIdentityTravelsWithSerializedMetadata()throws Exception{
        var json=new ObjectMapper();var selected=selection();var copy=json.readValue(json.writeValueAsBytes(selected),NativeCoderContext.class);
        assertEquals(selected,copy);assertEquals(selected.fingerprint(),copy.fingerprint());
        var node=(ObjectNode)json.valueToTree(selected);node.remove("identity");
        assertEquals(selected, json.treeToValue(node,NativeCoderContext.class));
        assertNotEquals(selected,selected.withIdentity("f".repeat(64)));
    }
    @Test void semanticIdsSurviveStoreRestartAndCompareExactDeclarations()throws Exception{
        String text="{\"schema\":1,\"classes\":[{\"module\":\"fixture\",\"classHash\":\""+"a".repeat(64)+"\",\"symbols\":{\"name\":\"Type\"}}]}";
        String id=NativeKnowledgeArtifact.semanticHash(text,new ContentAddressedStore(root));
        assertEquals(id,NativeKnowledgeArtifact.semanticHash(text,new ContentAddressedStore(root)));
        String changed=text.replace("Type","Other");assertFalse(NativeKnowledgeArtifact.sameContents(text,changed));
        assertNotEquals(id,NativeKnowledgeArtifact.semanticHash(changed,new ContentAddressedStore(root)));
    }
    @Test void legacySelectionRetainsItsStoredReferenceAndStableKnowledgeId()throws Exception{
        UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),id;
        Path db=root.resolve("runtime.db");var json=new ObjectMapper();var selected=selection();
        try(var store=NativeKnowledgeStore.open(db,world,Clock.systemUTC())){
            id=store.remember(UUID.randomUUID(),owner,agent,UUID.randomUUID(),UUID.randomUUID(),"known",selected,new NativeCoderContext.Snapshot(selected.fingerprint(),"d".repeat(64),512),"e".repeat(64)).entry().knowledgeId();
        }
        try(var repo=new SqliteRuntimeRepository(db)){
            var row=repo.list(world,"native_api_knowledge_v1").getFirst();var value=(ObjectNode)json.readTree(row.payload());
            ((ObjectNode)value.get("selection")).remove("identity");((ObjectNode)value.get("context")).put("selectionHash","9".repeat(64));
            value.put("lastFingerprint","8".repeat(64));value.put("revision",row.revision()+1);
            assertTrue(repo.compareAndSet(world,"native_api_knowledge_v1",id.toString(),row.revision(),json.writeValueAsString(value),System.currentTimeMillis()).accepted());
        }
        try(var store=NativeKnowledgeStore.open(db,world,Clock.systemUTC())){
            var legacy=store.get(owner,agent,id);assertEquals("9".repeat(64),legacy.selection().fingerprint());assertEquals(legacy.context().selectionHash(),legacy.selection().fingerprint());
            var rekeyed=store.revalidate(UUID.randomUUID(),owner,agent,id,legacy.selection(),legacy.context(),"7".repeat(64),true).entry();
            assertEquals("CURRENT",rekeyed.state());assertEquals(id,rekeyed.knowledgeId());
            var remembered=store.remember(UUID.randomUUID(),owner,agent,UUID.randomUUID(),UUID.randomUUID(),"renamed",legacy.selection(),legacy.context(),"7".repeat(64)).entry();
            assertEquals(id,remembered.knowledgeId());assertThrows(SecurityException.class,()->store.get(UUID.randomUUID(),agent,id));
        }
    }
}
