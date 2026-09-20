package dev.mineagent.runtime.core.feedback;

import java.util.Objects;

/** A stable event is ingested before its acknowledgement; never executes models or package code inside SQL. */
public final class UiFeedbackOutbox {
    @FunctionalInterface public interface EventSink {void ingest(UiFeedbackStore.Item accepted)throws Exception;}
    private final UiFeedbackStore store;private final EventSink events;
    public UiFeedbackOutbox(UiFeedbackStore store,EventSink events){this.store=Objects.requireNonNull(store);this.events=Objects.requireNonNull(events);}
    public int pump(int limit)throws Exception{
        int count=0;
        for(var accepted:store.pendingExports(limit)){
            events.ingest(store.exportable(accepted.id())); // Recheck after earlier sink callbacks; deduplicate by id.
            store.exported(accepted.id());count++;
        }
        return count;
    }
}
