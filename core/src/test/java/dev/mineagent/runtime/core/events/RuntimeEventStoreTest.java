package dev.mineagent.runtime.core.events;
import dev.mineagent.runtime.api.directory.ObjectRef;
import dev.mineagent.runtime.core.directory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEventStoreTest {
    @TempDir Path dir;private final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),task=UUID.randomUUID(),epoch=UUID.randomUUID();private final AtomicLong now=new AtomicLong(1000);private String authority="grant-1";private boolean allowed=true;
    private RuntimeEventStore.Context context(UUID operation){return new RuntimeEventStore.Context(new ObjectDirectory.Scope(world,agent,owner,task,1,1),epoch,1,operation);}
    private RuntimeEventStore.Request request(String mode,int wakes,int pending){return new RuntimeEventStore.Request(Set.of("PLAYER_JOIN"),mode,ObjectQuery.parse("{\"kind\":\"PLAYER\"}"),mode.equals("AGENT_WAKE")?"询问玩家需要什么帮助":"",wakes,mode.equals("AGENT_WAKE")?1:0,pending,0,3600);}
    private final Set<UUID> brokenFilters=new HashSet<>();
    private final RuntimeEventStore.Port port=new RuntimeEventStore.Port(){
        public void authorize(RuntimeEventStore.Context c){if(!allowed)throw new SecurityException("EVENT_FORBIDDEN");}
        public String authority(RuntimeEventStore.Subscription s){return allowed?authority:null;}
        public boolean matches(RuntimeEventStore.Subscription s,RuntimeEventStore.Event e){if(brokenFilters.contains(s.id()))throw new IllegalStateException("ACTOR_UNAVAILABLE");return true;}
    };
    private RuntimeEventStore open()throws Exception{return RuntimeEventStore.open(dir.resolve("runtime.db"),world,now::get,port);}
    private RuntimeEventStore.Event event(){var ref=new ObjectRef(world,ObjectRef.Kind.PLAYER,owner.toString(),UUID.randomUUID(),1);var subject=new ObjectDirectory.Entry(ref,"real source subject","minecraft:overworld",new ObjectQuery.Point("minecraft:overworld",0,64,0),"",true);return new RuntimeEventStore.Event(UUID.randomUUID(),world,epoch,"PLAYER_JOIN",owner,subject,now.get(),null,List.of());}
    @Test void recordModeDoesNotCreateWakeWorkAndDuplicateEventDoesNotRepeatConsumption()throws Exception{
        try(var store=open()){var sub=store.subscribe(context(UUID.randomUUID()),request("RECORD_ONLY",0,4));var event=event();store.ingest(event);store.ingest(event);var values=store.triggers(context(UUID.randomUUID()),sub.id());assertEquals(1,values.size());assertEquals("RECORDED",values.getFirst().state());assertNull(values.getFirst().taskId());assertTrue(store.claim().isEmpty());}
    }
    @Test void wakesRequireARealAcceptedEventAndAreClaimedOnceWithAPlanningBudget()throws Exception{
        try(var store=open()){var sub=store.subscribe(context(UUID.randomUUID()),request("AGENT_WAKE",2,4));assertTrue(store.claim().isEmpty());store.ingest(event());var trigger=store.claim().orElseThrow();assertNotNull(trigger.taskId());assertTrue(store.claim().isEmpty());store.dispatched(trigger.id());assertTrue(store.permitTask(trigger.taskId()));assertTrue(store.reserveModel(trigger.taskId()));assertFalse(store.reserveModel(trigger.taskId()));assertFalse(store.permitTask(trigger.taskId()));}
    }
    @Test void cancelOrRegrantCannotReviveQueuedAndDispatchedWork()throws Exception{
        try(var store=open()){var sub=store.subscribe(context(UUID.randomUUID()),request("AGENT_WAKE",4,4));store.ingest(event());authority="grant-2";assertTrue(store.claim().isEmpty());assertEquals("CANCELLED",store.triggers(context(UUID.randomUUID()),sub.id()).getFirst().state());
            store.ingest(event());var trigger=store.claim().orElseThrow();store.dispatched(trigger.id());store.state(context(UUID.randomUUID()),sub.id(),1,"PAUSED");assertFalse(store.permitTask(trigger.taskId()));store.state(context(UUID.randomUUID()),sub.id(),2,"ACTIVE");assertFalse(store.permitTask(trigger.taskId()));}
    }
    @Test void restartKeepsSubscriptionsAndCursorsButNeverReplaysUnknownWakeWork()throws Exception{
        UUID id;RuntimeEventStore.Event event=event();UUID child;
        try(var store=open()){var sub=store.subscribe(context(UUID.randomUUID()),request("AGENT_WAKE",4,4));id=sub.id();store.ingest(event);var t=store.claim().orElseThrow();child=t.taskId();}
        try(var store=open()){assertEquals("ACTIVE",store.inspect(context(UUID.randomUUID()),id).state());assertEquals("INTERRUPTED",store.triggers(context(UUID.randomUUID()),id).getFirst().state());assertFalse(store.permitTask(child));store.ingest(event);assertTrue(store.claim().isEmpty());store.ingest(event());assertTrue(store.claim().isPresent());}
    }
    @Test void operationAndEventIdentitiesCannotBeReusedForDifferentData()throws Exception{
        try(var store=open()){UUID op=UUID.randomUUID();var a=store.subscribe(context(op),request("RECORD_ONLY",0,4));assertEquals(a,store.subscribe(context(op),request("RECORD_ONLY",0,4)));assertThrows(IllegalArgumentException.class,()->store.subscribe(context(op),request("AGENT_WAKE",2,4)));
            var event=event();store.ingest(event);var changed=new RuntimeEventStore.Event(event.id(),world,epoch,event.source(),owner,event.subject(),event.occurredAt()+1,null,List.of());assertThrows(IllegalArgumentException.class,()->store.ingest(changed));}
    }
    @Test void backpressureAndCausalCyclesAreVisibleAndDoNotCreateExtraTasks()throws Exception{
        try(var store=open()){
            var sub=store.subscribe(context(UUID.randomUUID()),request("AGENT_WAKE",4,1));var first=event();
            var cycle=new RuntimeEventStore.Event(first.id(),world,epoch,first.source(),owner,first.subject(),now.get(),null,List.of(sub.id()));
            store.ingest(cycle);assertEquals("CYCLE_REJECTED",store.triggers(context(UUID.randomUUID()),sub.id()).getFirst().state());
            store.ingest(event());store.ingest(event());
            // Per-event queue pressure records the rejected wake; it does not revoke the already queued task.
            assertEquals("ACTIVE",store.inspect(context(UUID.randomUUID()),sub.id()).state());var rows=store.triggers(context(UUID.randomUUID()),sub.id());
            assertEquals(1,rows.stream().filter(t->t.state().equals("BACKPRESSURE")).count());assertEquals(1,rows.stream().filter(t->t.state().equals("QUEUED")).count());
            assertTrue(store.claim().isPresent());assertTrue(store.claim().isEmpty());
        }
    }
    @Test void oldEventsAreNotRetroactivelyConsumedAndExpiredSubscriptionsCannotWake()throws Exception{
        try(var store=open()){var old=event();store.ingest(old);var sub=store.subscribe(context(UUID.randomUUID()),request("AGENT_WAKE",2,4));store.ingest(old);assertTrue(store.triggers(context(UUID.randomUUID()),sub.id()).isEmpty());now.set(4_000_000);store.ingest(event());assertTrue(store.claim().isEmpty());}
    }
    @Test void oneUnavailableFilterDoesNotDiscardTheRealEventOrOtherSubscriptions()throws Exception{
        try(var store=open()){var a=store.subscribe(context(UUID.randomUUID()),request("RECORD_ONLY",0,4));var b=store.subscribe(context(UUID.randomUUID()),request("RECORD_ONLY",0,4));brokenFilters.add(a.id());var event=event();store.ingest(event);assertTrue(store.event(event.id()).isPresent());assertEquals("PAUSED",store.inspect(context(UUID.randomUUID()),a.id()).state());assertEquals(1,store.triggers(context(UUID.randomUUID()),b.id()).size());}
    }
}
