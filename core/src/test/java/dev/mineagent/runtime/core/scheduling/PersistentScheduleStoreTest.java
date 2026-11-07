package dev.mineagent.runtime.core.scheduling;
import dev.mineagent.runtime.core.directory.ObjectDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PersistentScheduleStoreTest {
    @TempDir Path dir;private final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),task=UUID.randomUUID(),epoch=UUID.randomUUID();
    private final AtomicLong wall=new AtomicLong(1000),ticks=new AtomicLong(100);private String authority="a1";private long signal;private boolean allowed=true,signalAvailable=true;
    private PersistentScheduleStore.Context ctx(){return context(UUID.randomUUID());}
    private PersistentScheduleStore.Context context(UUID op){return new PersistentScheduleStore.Context(new ObjectDirectory.Scope(world,agent,owner,task,1,1),epoch,op);}
    private final PersistentScheduleStore.Port port=new PersistentScheduleStore.Port(){public void authorize(PersistentScheduleStore.Context c){if(!allowed)throw new SecurityException("SCHEDULE_DENIED");}public String authority(PersistentScheduleStore.Definition d){return allowed?authority:null;}public PersistentScheduleStore.Signal signal(PersistentScheduleStore.Definition d){if(!signalAvailable)throw new IllegalStateException("CONDITION_UNAVAILABLE");return new PersistentScheduleStore.Signal(signal,signal==0?null:new UUID(0,signal));}};
    private PersistentScheduleStore open(String file)throws Exception{return PersistentScheduleStore.open(dir.resolve(file),world,wall::get,ticks::get,port);}
    private ScheduleSpec wall(String kind,String missed){return new ScheduleSpec(kind,"RECORD_ONLY","",null,1000,kind.equals("WALL_PERIODIC")?1000:0,kind.equals("WALL_PERIODIC")?10:1,60000,"Asia/Shanghai",missed,250,null,0,false,0);}
    @Test void oneShotIsDurableAndNeverRepeatsAfterClockRollback()throws Exception{
        UUID id;try(var store=open("once.db")){var d=store.create(ctx(),wall("WALL_ONCE","SKIP"));id=d.id();store.poll();assertTrue(store.occurrences(ctx(),id).isEmpty());wall.set(2100);store.poll();assertEquals(1,store.occurrences(ctx(),id).size());wall.set(1000);store.poll();assertEquals(1,store.occurrences(ctx(),id).size());}
        try(var store=open("once.db")){wall.set(4000);store.poll();assertEquals(1,store.occurrences(ctx(),id).size());}
    }
    @Test void restartSkipsMissedPeriodsOrCoalescesOnlyTheLatestUnexpiredOccurrence()throws Exception{
        UUID skip,coalesce;try(var a=open("skip.db");var b=open("coalesce.db")){skip=a.create(ctx(),wall("WALL_PERIODIC","SKIP")).id();coalesce=b.create(ctx(),wall("WALL_PERIODIC","COALESCE")).id();wall.set(2100);a.poll();b.poll();}
        wall.set(4500);try(var a=open("skip.db");var b=open("coalesce.db")){a.poll();b.poll();assertEquals(1,a.occurrences(ctx(),skip).size());var values=b.occurrences(ctx(),coalesce);assertEquals(2,values.size());assertEquals(2,values.getLast().index());assertEquals(2,a.inspect(ctx(),skip).skipped());}
    }
    @Test void wallTimeDoesNotAdvanceTheTickClock()throws Exception{
        var spec=new ScheduleSpec("TICK_ONCE","RECORD_ONLY","",null,5,0,1,604800000,"UTC","SKIP",0,null,0,false,0);
        try(var store=open("ticks.db")){var d=store.create(ctx(),spec);wall.set(86401000);store.poll();assertTrue(store.occurrences(ctx(),d.id()).isEmpty());ticks.set(105);store.poll();assertEquals(1,store.occurrences(ctx(),d.id()).size());ticks.set(101);store.poll();assertEquals(1,store.occurrences(ctx(),d.id()).size());}
    }
    @Test void expiredOneShotIsNotExecuted()throws Exception{
        try(var store=open("expired.db")){var d=store.create(ctx(),new ScheduleSpec("WALL_ONCE","RECORD_ONLY","",2000L,0,0,1,1000,"UTC","SKIP",250,null,0,false,0));wall.set(4000);store.poll();assertEquals("EXPIRED",store.inspect(ctx(),d.id()).state());assertTrue(store.occurrences(ctx(),d.id()).isEmpty());}
    }
    @Test void eventConditionsRespectTheExplicitInitialMatchPolicy()throws Exception{
        signal=3;UUID source=UUID.randomUUID();try(var store=open("condition.db")){var yes=store.create(ctx(),new ScheduleSpec("EVENT_CONDITION","RECORD_ONLY","",null,0,0,1,60000,"UTC","SKIP",0,source,1,true,0));var no=store.create(ctx(),new ScheduleSpec("EVENT_CONDITION","RECORD_ONLY","",null,0,0,1,60000,"UTC","SKIP",0,source,1,false,0));store.poll();assertEquals(1,store.occurrences(ctx(),yes.id()).size());assertTrue(store.occurrences(ctx(),no.id()).isEmpty());signal=4;store.poll();assertEquals(1,store.occurrences(ctx(),no.id()).size());}
    }
    @Test void pauseRegrantAndRestartNeverReviveAnOldClaimedOccurrence()throws Exception{
        UUID id,child;try(var store=open("wake.db")){var d=store.create(ctx(),new ScheduleSpec("WALL_ONCE","AGENT_WAKE","ask an ordinary question",null,0,0,1,60000,"UTC","SKIP",250,null,0,false,1));id=d.id();store.poll();var occurrence=store.claim().orElseThrow();child=occurrence.taskId();store.dispatched(occurrence.id());assertTrue(store.reserveModel(child));assertFalse(store.reserveModel(child));assertFalse(store.permitTask(child));}
        try(var store=open("wake.db")){assertTrue(store.claim().isEmpty());assertFalse(store.permitTask(child));assertEquals(1,store.occurrences(ctx(),id).size());}
    }
    @Test void operationFingerprintsAndAuthorityProtectCachedDefinitions()throws Exception{
        UUID operation=UUID.randomUUID();try(var store=open("ops.db")){var d=store.create(context(operation),wall("WALL_ONCE","SKIP"));assertEquals(d,store.create(context(operation),wall("WALL_ONCE","SKIP")));assertThrows(IllegalArgumentException.class,()->store.create(context(operation),wall("WALL_PERIODIC","SKIP")));allowed=false;assertThrows(SecurityException.class,()->store.create(context(operation),wall("WALL_ONCE","SKIP")));}
    }
    @Test void dstRepeatedLocalTimeStillHasTwoDistinctUtcOccurrences()throws Exception{
        wall.set(Instant.parse("2026-11-01T05:29:00Z").toEpochMilli());var at=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli();try(var store=open("dst.db")){var d=store.create(ctx(),new ScheduleSpec("WALL_PERIODIC","RECORD_ONLY","",at,0,3600000,2,60000,"America/New_York","COALESCE",250,null,0,false,0));wall.set(at);store.poll();wall.set(at+3600000);store.poll();var values=store.occurrences(ctx(),d.id());assertEquals(2,values.size());assertNotEquals(values.get(0).id(),values.get(1).id());}
    }
    @Test void pauseAndRegrantDoNotRequeueTheOldSlot()throws Exception{
        try(var store=open("pause.db")){var d=store.create(ctx(),new ScheduleSpec("WALL_PERIODIC","AGENT_WAKE","test",null,0,1000,3,60000,"UTC","COALESCE",250,null,0,false,1));store.poll();var first=store.claim().orElseThrow();store.dispatched(first.id());store.state(ctx(),d.id(),1,"PAUSED");assertFalse(store.permitTask(first.taskId()));store.state(ctx(),d.id(),2,"ACTIVE");assertFalse(store.permitTask(first.taskId()));wall.set(2100);store.poll();authority="a2";assertTrue(store.claim().isEmpty());assertEquals(2,store.occurrences(ctx(),d.id()).size());}
    }
    @Test void initialConditionRequiresAdmissionAndOneBrokenConditionDoesNotBlockOtherClocks()throws Exception{
        var spec=new ScheduleSpec("EVENT_CONDITION","RECORD_ONLY","",null,0,0,1,60000,"UTC","SKIP",0,UUID.randomUUID(),1,true,0);
        try(var store=open("bad-condition.db")){signalAvailable=false;assertThrows(IllegalStateException.class,()->store.create(ctx(),spec));signalAvailable=true;var condition=store.create(ctx(),spec);var timer=store.create(ctx(),wall("WALL_ONCE","SKIP"));signalAvailable=false;wall.set(2100);store.poll();assertEquals("PAUSED",store.inspect(ctx(),condition.id()).state());assertEquals(1,store.occurrences(ctx(),timer.id()).size());}
    }
    @Test void aClaimWithoutADurableDispatchResultIsInterruptedOnRestart()throws Exception{
        UUID schedule,child;try(var store=open("unknown.db")){var d=store.create(ctx(),new ScheduleSpec("WALL_ONCE","AGENT_WAKE","bounded question",null,0,0,1,60000,"UTC","SKIP",250,null,0,false,1));schedule=d.id();store.poll();child=store.claim().orElseThrow().taskId();}
        try(var store=open("unknown.db")){assertEquals("INTERRUPTED",store.occurrences(ctx(),schedule).getFirst().state());assertFalse(store.permitTask(child));assertTrue(store.claim().isEmpty());}
    }
    @Test void legacyCreateReceiptRetainsScheduleAndDoesNotRefireAnOccurrence()throws Exception{
        UUID operation=UUID.randomUUID(),id,occurrence;var context=context(operation);var spec=wall("WALL_ONCE","SKIP");
        try(var store=open("migration.db")){id=store.create(context,spec).id();wall.set(2100);store.poll();occurrence=store.occurrences(ctx(),id).getFirst().id();}
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("migration.db"));var update=db.prepareStatement("UPDATE mineagent_schedule_operations_v1 SET payload=json_set(payload,'$.fingerprint',?) WHERE world=? AND id=?")){
            update.setString(1,"b".repeat(64));update.setString(2,world.toString());update.setString(3,operation.toString());assertEquals(1,update.executeUpdate());
        }
        try(var store=open("migration.db")){assertEquals(id,store.create(context,spec).id());store.poll();assertEquals(1,store.occurrences(ctx(),id).size());assertEquals(occurrence,store.occurrences(ctx(),id).getFirst().id());assertThrows(IllegalArgumentException.class,()->store.create(context,wall("WALL_PERIODIC","SKIP")));}
    }

}
