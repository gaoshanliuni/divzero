package dev.mineagent.runtime.core.shared;

import dev.mineagent.runtime.core.events.RuntimeEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SharedEventBridgeTest {
    @TempDir Path root;
    final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),actor=UUID.randomUUID(),pack=UUID.randomUUID(),instance=UUID.randomUUID();
    SharedStateStore.Context context(){return new SharedStateStore.Context(new SharedStateStore.Scope(world,pack,instance,"state"),owner,actor,1,"a".repeat(64),"AGENT");}
    SharedStateStore open()throws Exception{return SharedStateStore.open(root.resolve("runtime.db"),world,(c,w,a)->true);}
    void create(SharedStateStore store)throws Exception{store.define(context(),UUID.randomUUID(),0,"{\"version\":1,\"fields\":{\"count\":{\"type\":\"INTEGER\",\"scope\":\"SHARED\"},\"privateValue\":{\"type\":\"STRING\"}}}");}
    @Test void committedStateNoticesRetryWithIdenticalImmutableIdentityAfterIngestBeforeAckFailure()throws Exception{
        var received=new LinkedHashMap<UUID,RuntimeEventStore.Event>();AtomicBoolean fail=new AtomicBoolean(true);
        try(var s=open()){create(s);s.transact(context(),UUID.randomUUID(),"{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1},{\"key\":\"privateValue\",\"op\":\"PUT\",\"value\":\"not an event payload\"}]}");
            var bridge=new SharedEventBridge(s,event->{received.putIfAbsent(event.id(),event);if(fail.getAndSet(false))throw new IllegalStateException("AFTER_INGEST_BEFORE_ACK");});assertThrows(IllegalStateException.class,()->bridge.forward(16));assertEquals(2,s.pendingExports(16).size());}
        try(var s=open()){var bridge=new SharedEventBridge(s,event->{var old=received.putIfAbsent(event.id(),event);if(old!=null)assertEquals(old,event);});assertEquals(2,bridge.forward(16));assertEquals(2,received.size());assertTrue(s.pendingExports(16).isEmpty());assertEquals(0,bridge.forward(16));
            var event=received.values().stream().filter(e->e.shared().revision()==2).findFirst().orElseThrow();assertEquals("SHARED_STATE_CHANGED",event.source());assertNull(event.subject());assertEquals(actor,event.author());assertEquals(instance,event.shared().target().instanceId());assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event).contains("not an event payload"));}
    }
    @Test void selectedReadsAndSchemaDescriptionNeverRevealUnrequestedOrOwnerOnlyFields()throws Exception{
        try(var s=open()){create(s);s.transact(context(),UUID.randomUUID(),"{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1},{\"key\":\"privateValue\",\"op\":\"PUT\",\"value\":\"private\"}]}");assertEquals(Set.of("count"),s.read(context(),Set.of("count")).values().keySet());assertEquals(2,s.describe(context()).fields().size());assertThrows(IllegalArgumentException.class,()->s.read(context(),Set.of("unknown")));}
    }
    @Test void agentProvenanceIsBoundToOperationAndCarriedWithoutPrivateValues(){
        assertThrows(IllegalArgumentException.class,()->new SharedStateStore.Provenance(UUID.randomUUID(),1,List.of(owner,owner)));
    }
}
