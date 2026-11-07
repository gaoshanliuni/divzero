package dev.mineagent.runtime.core.events;

import dev.mineagent.runtime.core.directory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventIdentityTest {
    @TempDir Path root;
    final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),task=UUID.randomUUID(),epoch=UUID.randomUUID();
    RuntimeEventStore.Context context(UUID id){return new RuntimeEventStore.Context(new ObjectDirectory.Scope(world,agent,owner,task,1,1),epoch,1,id);}
    RuntimeEventStore open()throws Exception{return RuntimeEventStore.open(root.resolve("events.db"),world,()->1000L,new RuntimeEventStore.Port(){
        public void authorize(RuntimeEventStore.Context c){}
        public String authority(RuntimeEventStore.Subscription s){return "grant-1";}
        public boolean matches(RuntimeEventStore.Subscription s,RuntimeEventStore.Event e){return e.score()==null||ScoreEventFilter.matches(s,e);}
    });}
    @Test void playerSourceIdentitiesSurviveReopenAndSeparateConnectionsAndKinds()throws Exception{
        UUID generation=UUID.randomUUID(),joined,left;
        try(var store=open()){joined=store.playerEventId(epoch,"PLAYER_JOIN",generation);left=store.playerEventId(epoch,"PLAYER_LEAVE",generation);assertNotEquals(joined,left);assertNotEquals(joined,store.playerEventId(epoch,"PLAYER_JOIN",UUID.randomUUID()));}
        try(var store=open()){assertEquals(joined,store.playerEventId(epoch,"PLAYER_JOIN",generation));assertEquals(left,store.playerEventId(epoch,"PLAYER_LEAVE",generation));assertNotEquals(joined,store.playerEventId(UUID.randomUUID(),"PLAYER_JOIN",generation));assertThrows(IllegalArgumentException.class,()->store.playerEventId(epoch,"SCORE_CHANGED",generation));}
    }
    @Test void uncertainScoreIngestRetainsOneEventAndChecksExactCurrentAuthority()throws Exception{
        var filter=new ScoreEventFilter("points","dummy",List.of("player"),"ANY_CHANGE",null,"ON_CHANGE","PAUSE");
        var request=new RuntimeEventStore.Request(Set.of(ScoreEventFilter.SOURCE),"RECORD_ONLY",null,"",0,0,4,0,3600,null,null,null,filter);
        try(var store=open()){
            var context=context(UUID.randomUUID());var sub=store.subscribe(context,request);var sampler=new ScoreEventSampler();
            sampler.observe(sub,"security","grant-1",epoch,1,Map.of("player",1),100);
            sampler.observe(sub,"security","grant-1",epoch,1,Map.of("player",2),200);
            var seen=new ArrayList<RuntimeEventStore.Event>();
            assertThrows(java.io.IOException.class,()->sampler.drain(sub,"security","grant-1",1,4,e->{seen.add(e);store.ingest(e);throw new java.io.IOException("lost acknowledgement");}));
            assertThrows(SecurityException.class,()->sampler.drain(sub,"security","grant-2",1,4,e->fail("stale source reached store")));
            assertEquals(1,sampler.drain(sub,"security","grant-1",1,4,e->{assertEquals(seen.getFirst(),e);assertEquals(1,store.ingest(e));}));
            assertEquals(1,store.triggers(context,sub.id()).size());assertEquals(epoch,seen.getFirst().author());
            var e=seen.getFirst();assertThrows(IllegalArgumentException.class,()->new RuntimeEventStore.Event(UUID.randomUUID(),world,epoch,e.source(),UUID.randomUUID(),null,e.occurredAt(),null,List.of(),null,null,null,null,e.score()));
            assertEquals("MONITORING",sampler.status(sub).phase());
        }
    }
    @Test void legacySubscriptionReceiptIsAdoptedWithoutCreatingAnotherSubscription()throws Exception{
        UUID operation=UUID.randomUUID(),id;var context=context(operation);var request=new RuntimeEventStore.Request(Set.of("PLAYER_JOIN"),"RECORD_ONLY",ObjectQuery.parse("{\"kind\":\"PLAYER\"}"),"",0,0,4,0,3600);
        try(var store=open()){id=store.subscribe(context,request).id();}
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("events.db"));var update=db.prepareStatement("UPDATE mineagent_event_operations_v1 SET payload=json_set(payload,'$.fingerprint',?) WHERE world=? AND id=?")){
            update.setString(1,"a".repeat(64));update.setString(2,world.toString());update.setString(3,operation.toString());assertEquals(1,update.executeUpdate());
        }
        try(var store=open()){assertEquals(id,store.subscribe(context,request).id());assertEquals(id,store.subscribe(context,request).id());assertEquals(1,store.subscriptionsForRuntime().size());
            var changed=new RuntimeEventStore.Request(Set.of("PLAYER_LEAVE"),"RECORD_ONLY",request.query(),"",0,0,4,0,3600);assertThrows(IllegalArgumentException.class,()->store.subscribe(context,changed));}
    }
}
