package dev.mineagent.runtime.core.compile;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeKnowledgeStoreTest {
    @TempDir Path root;final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),task=UUID.randomUUID(),source=UUID.randomUUID();NativeKnowledgeStore store;
    NativeCoderContext selection(String snapshot,String moduleHash,String className){return new NativeCoderContext(snapshot,"b".repeat(64),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(new NativeCoderContext.Type("minecraft",moduleHash,className)));}
    NativeCoderContext.Snapshot context(NativeCoderContext value,String hash){return new NativeCoderContext.Snapshot(value.fingerprint(),hash,512);}
    @BeforeEach void open()throws Exception{store=NativeKnowledgeStore.open(root.resolve("runtime.db"),world,Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC));}
    @AfterEach void close()throws Exception{if(store!=null)store.close();}
    @Test void remembersSearchesRevalidatesAndPersistsOwnerAgentScopedKnowledge()throws Exception{
        var first=selection("a".repeat(64),"c".repeat(64),"net.minecraft.server.MinecraftServer");UUID operation=UUID.randomUUID();var saved=store.remember(operation,owner,agent,task,source,"server tick",first,context(first,"d".repeat(64)),"e".repeat(64));assertFalse(saved.duplicate());assertEquals("CURRENT",saved.entry().state());
        assertTrue(store.remember(operation,owner,agent,task,source,"server tick",first,context(first,"d".repeat(64)),"e".repeat(64)).duplicate());assertThrows(IllegalArgumentException.class,()->store.remember(operation,owner,agent,task,source,"changed",first,context(first,"d".repeat(64)),"e".repeat(64)));
        assertEquals(1,store.search(owner,agent,"minecraftserver",0).entries().size());assertTrue(store.search(owner,UUID.randomUUID(),"",0).entries().isEmpty());
        var equivalent=selection(first.snapshot(),first.types().getFirst().moduleHash(),first.types().getFirst().className());var current=store.revalidate(UUID.randomUUID(),owner,agent,saved.entry().knowledgeId(),equivalent,context(equivalent,"2".repeat(64)),"e".repeat(64));assertEquals("CURRENT",current.entry().state());assertEquals(equivalent,current.entry().selection());
        var changed=selection("3".repeat(64),"4".repeat(64),first.types().getFirst().className());var review=store.revalidate(UUID.randomUUID(),owner,agent,saved.entry().knowledgeId(),changed,context(changed,"5".repeat(64)),"6".repeat(64));assertEquals("CHANGED_REVIEW_REQUIRED",review.entry().state());assertEquals(changed,review.entry().candidateSelection());
        store.close();store=NativeKnowledgeStore.open(root.resolve("runtime.db"),world,Clock.systemUTC());assertEquals("CHANGED_REVIEW_REQUIRED",store.get(owner,agent,saved.entry().knowledgeId()).state());
        var refreshed=store.remember(UUID.randomUUID(),owner,agent,task,UUID.randomUUID(),"reviewed update",changed,context(changed,"5".repeat(64)),"6".repeat(64));assertEquals(saved.entry().knowledgeId(),refreshed.entry().knowledgeId());assertEquals("CURRENT",refreshed.entry().state());
        var forgotten=store.forget(UUID.randomUUID(),owner,agent,saved.entry().knowledgeId());assertEquals("FORGOTTEN",forgotten.entry().state());assertTrue(store.search(owner,agent,"",0).entries().isEmpty());assertThrows(IllegalStateException.class,()->store.revalidate(UUID.randomUUID(),owner,agent,saved.entry().knowledgeId(),changed,context(changed,"5".repeat(64)),"6".repeat(64)));
    }
    @Test void currentSelectionsRequireLiveSnapshotEnvironmentAndCurrentState()throws Exception{
        var value=selection("a".repeat(64),"c".repeat(64),"net.minecraft.server.MinecraftServer");var saved=store.remember(UUID.randomUUID(),owner,agent,task,source,"server",value,context(value,"d".repeat(64)),"e".repeat(64)).entry();assertEquals(value,store.current(owner,agent,List.of(saved.knowledgeId()),value.snapshot(),value.environment()).getFirst());
        assertThrows(IllegalStateException.class,()->store.current(owner,agent,List.of(saved.knowledgeId()),"f".repeat(64),value.environment()));var moved=selection("f".repeat(64),"1".repeat(64),value.types().getFirst().className());var review=store.revalidate(UUID.randomUUID(),owner,agent,saved.knowledgeId(),moved,context(moved,"2".repeat(64)),"e".repeat(64)).entry();assertEquals("CHANGED_REVIEW_REQUIRED",review.state());assertEquals("PROVENANCE_CHANGED",review.diagnostic());store.markMissing(UUID.randomUUID(),owner,agent,saved.knowledgeId(),"CLASS_MISSING");assertThrows(IllegalStateException.class,()->store.current(owner,agent,List.of(saved.knowledgeId()),value.snapshot(),value.environment()));
    }
    @Test void completedMutationOperationRemainsIdempotentAcrossRestart()throws Exception{
        var value=selection("a".repeat(64),"c".repeat(64),"net.minecraft.server.MinecraftServer");UUID operation=UUID.randomUUID();var first=store.remember(operation,owner,agent,task,source,"server",value,context(value,"d".repeat(64)),"e".repeat(64));store.close();store=NativeKnowledgeStore.open(root.resolve("runtime.db"),world,Clock.systemUTC());var replay=store.remember(operation,owner,agent,task,source,"server",value,context(value,"d".repeat(64)),"e".repeat(64));assertTrue(replay.duplicate());assertEquals(first.entry().knowledgeId(),replay.entry().knowledgeId());
    }
}
