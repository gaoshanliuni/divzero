package dev.mineagent.runtime.core.shared;

import dev.mineagent.runtime.core.events.RuntimeEventStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** At-least-once immutable export. Ingest precedes acknowledgement; the event store owns consumer deduplication. */
public final class SharedEventBridge {
    @FunctionalInterface public interface Sink{void ingest(RuntimeEventStore.Event event)throws Exception;}
    private final SharedStateStore source;private final Sink sink;
    public SharedEventBridge(SharedStateStore source,Sink sink){this.source=source;this.sink=sink;}
    public int forward(int limit)throws Exception{int count=0;for(var row:source.pendingExports(limit)){
        var e=row.event();var target=new SharedStateTarget(row.scope().pack(),row.scope().instance(),e.packageRevision(),e.canonical(),row.scope().namespace());
        var change=new RuntimeEventStore.SharedChange(target,e.revision(),e.schema(),e.actorKind(),e.changes(),e.origin());
        UUID epoch=UUID.nameUUIDFromBytes(("shared-state-stream-v1|"+row.scope().world()).getBytes(StandardCharsets.UTF_8));
        var event=new RuntimeEventStore.Event(e.id(),row.scope().world(),epoch,"SHARED_STATE_CHANGED",e.author(),null,e.occurredAt(),e.operation(),e.origin().subscriptionChain(),change);
        sink.ingest(event);source.acknowledgeExport(row);count++;
    }return count;}
}
