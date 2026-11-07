package dev.mineagent.runtime.core.feedback;
import dev.mineagent.runtime.core.events.RuntimeEventStore;
import dev.mineagent.runtime.core.directory.ObjectDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FeedbackWakeStoreTest {
    @TempDir Path root;
    @Test void acceptedBeforeSubscriptionDoesNotWakeAndPausedConsumerCannotOverlapAReplacement()throws Exception{
        var h=new UiFeedbackStoreTest();h.root=root;var scope=h.scope("AGENT_WAKE","INDEPENDENT");
        var filter=new FeedbackSubscriptionFilter(scope.packageId(),scope.packageRevision(),scope.canonicalSha256(),scope.entry(),scope.policySha256(),Set.of("help"),0);
        var c=new RuntimeEventStore.Context(new ObjectDirectory.Scope(h.world,h.agent,h.owner,UUID.randomUUID(),1,1),UUID.randomUUID(),1,UUID.randomUUID());
        var request=new RuntimeEventStore.Request(Set.of("UI_FEEDBACK"),"AGENT_WAKE",null,"解释反馈",4,3,4,0,3600,null,filter);
        try(var feedback=h.open();var events=RuntimeEventStore.open(root.resolve("events.db"),h.world,h.now::get,new RuntimeEventStore.Port(){
            public void authorize(RuntimeEventStore.Context c){}
            public String authority(RuntimeEventStore.Subscription s){return "grant";}
            public boolean matches(RuntimeEventStore.Subscription s,RuntimeEventStore.Event e){return s.request().feedback().matches(s.creator(),e);}
            public RuntimeEventStore.Request bindAtCreation(RuntimeEventStore.Context c,RuntimeEventStore.Request r){try{return new RuntimeEventStore.Request(r.sources(),r.mode(),null,r.goal(),r.maxWakes(),r.maxModelCalls(),r.pendingLimit(),r.cooldownMillis(),r.ttlSeconds(),null,r.feedback().after(feedback.head()));}catch(Exception e){throw new RuntimeException(e);}}
        })){
            var old=h.submit(feedback,scope,UUID.randomUUID());var sub=events.subscribe(c,request);events.ingest(events.feedbackEvent(old));assertTrue(events.triggers(c,sub.id()).isEmpty());
            h.now.addAndGet(1000);var fresh=h.submit(feedback,scope,UUID.randomUUID());events.ingest(events.feedbackEvent(fresh));events.ingest(events.feedbackEvent(fresh));assertEquals(1,events.triggers(c,sub.id()).size());assertEquals(sub.id(),events.subscribe(c,request).id());
            var c2=new RuntimeEventStore.Context(c.scope(),c.serverEpoch(),1,UUID.randomUUID());assertThrows(IllegalStateException.class,()->events.subscribe(c2,request));
            events.state(new RuntimeEventStore.Context(c.scope(),c.serverEpoch(),1,UUID.randomUUID()),sub.id(),1,"PAUSED");var replacement=events.subscribe(c2,request);assertNotEquals(sub.id(),replacement.id());
            assertThrows(IllegalStateException.class,()->events.state(new RuntimeEventStore.Context(c.scope(),c.serverEpoch(),1,UUID.randomUUID()),sub.id(),2,"ACTIVE"));
        }
    }
}
