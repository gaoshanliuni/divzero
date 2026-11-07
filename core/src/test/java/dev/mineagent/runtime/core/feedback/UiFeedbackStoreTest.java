package dev.mineagent.runtime.core.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class UiFeedbackStoreTest {
    @Test void reservedDataPlanSurvivesRestartAsInterruptedAndIsNotAutomaticallyCompleted()throws Exception{
        UUID id,consumer=UUID.randomUUID();
        var binding=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count"),Set.of("count"));
        var plan=FeedbackTransactionPlan.parse(binding,"{\"transaction\":{\"schema_version\":1,\"conditions\":[{\"key\":\"count\",\"test\":\"EXISTS\"}],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1}]}}");
        try(var s=open()){id=submit(s,scope("DETERMINISTIC","INDEPENDENT").withDataBinding(binding),UUID.randomUUID()).id();s.exported(id);s.processing(id,consumer);s.reserveDataPlan(id,consumer,plan);}
        try(var s=open()){
            var item=s.inspect(author,id);assertEquals("INTERRUPTED",item.state());assertEquals(plan,item.dataPlan());assertEquals("SERVER_RESTART_NO_REPLAY",item.error());assertNull(item.completion());assertTrue(s.pendingExports(4).isEmpty());
            assertThrows(IllegalStateException.class,()->s.reserveDataPlan(id,consumer,plan));
            assertThrows(IllegalStateException.class,()->s.dataResult(id,new UiFeedbackStore.Completion(id,"SHARED_TRANSACTION_APPLIED","{}")));
        }
    }
    @Test void deterministicPlanIsReservedOnceAndRejectedBusinessResultIsNotCompleted()throws Exception{
        try(var s=open()){var binding=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count"),Set.of("count"));var scope=scope("DETERMINISTIC","INDEPENDENT").withDataBinding(binding);var item=submit(s,scope,UUID.randomUUID());s.exported(item.id());UUID consumer=UUID.randomUUID();s.processing(item.id(),consumer);
            String source="{\"transaction\":{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1}]}}";var plan=FeedbackTransactionPlan.parse(binding,source);assertEquals(plan,s.reserveDataPlan(item.id(),consumer,plan));assertEquals(plan,s.reserveDataPlan(item.id(),consumer,plan));assertThrows(IllegalStateException.class,()->s.reserveDataPlan(item.id(),consumer,FeedbackTransactionPlan.parse(binding,source.replace("value\":1","value\":0"))));
            assertThrows(IllegalStateException.class,()->s.complete(item.id(),new UiFeedbackStore.Completion(item.id(),"SHARED_TRANSACTION_CONFLICT","{\"status\":\"CONFLICT\"}")));
            assertThrows(IllegalStateException.class,()->s.reserveReply(item.id(),consumer,UUID.randomUUID(),"not a model consumer"));
            s.dataResult(item.id(),new UiFeedbackStore.Completion(item.id(),"SHARED_TRANSACTION_CONFLICT","{\"status\":\"CONFLICT\"}"));assertEquals("REJECTED",s.inspect(author,item.id()).state());assertNotNull(s.inspect(author,item.id()).completion());
            completion=false;assertThrows(SecurityException.class,()->s.dataResult(item.id(),s.inspect(author,item.id()).completion()));
        }
    }
    @Test void oneReplyIntentIsDurableAndCannotBeReplacedOrBorrowAnotherConsumer()throws Exception{
        UUID id,consumer=UUID.randomUUID(),operation=UUID.randomUUID();
        try(var s=open()){id=submit(s,scope("AGENT_WAKE","INDEPENDENT"),UUID.randomUUID()).id();s.exported(id);s.processing(id,consumer);var reply=s.reserveReply(id,consumer,operation,"只回复此作者");assertEquals(reply,s.reserveReply(id,consumer,operation,"只回复此作者"));assertThrows(SecurityException.class,()->s.reserveReply(id,UUID.randomUUID(),operation,"只回复此作者"));assertThrows(IllegalStateException.class,()->s.reserveReply(id,consumer,UUID.randomUUID(),"changed"));assertEquals(1,s.inspect(author,id).sequence());assertEquals(1,s.head());}
        try(var s=open()){assertEquals("INTERRUPTED",s.inspect(author,id).state());assertEquals(operation,s.inspect(author,id).reply().operationId());assertThrows(IllegalStateException.class,()->s.reserveReply(id,consumer,operation,"只回复此作者"));}
    }
    @TempDir Path root;
    final UUID world=UUID.randomUUID(),delivery=UUID.randomUUID(),author=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),origin=UUID.randomUUID(),conversation=UUID.randomUUID(),pkg=UUID.randomUUID();
    final AtomicLong now=new AtomicLong(1000);boolean live=true,standing=true,completion=true,denyAtCommit;int checks;
    UiFeedbackPolicy policy(String mode,String lifecycle){return UiFeedbackPolicy.parse(UiFeedbackPolicyTest.declaration(mode,lifecycle).getBytes(StandardCharsets.UTF_8),"ui/index.html");}
    UiFeedbackStore.Scope scope(String mode,String lifecycle){var p=policy(mode,lifecycle);return new UiFeedbackStore.Scope(world,delivery,author,author,author,"PLAYER",owner,agent,origin,1,conversation,pkg,1,"a".repeat(64),"ui/index.html",p.sha256(),"help",p.event("help"),"grant-1",UUID.randomUUID(),List.of());}
    UiFeedbackStore.Document document(){UUID lease=UUID.randomUUID();return new UiFeedbackStore.Document(lease,UUID.randomUUID(),"delivery-"+lease,UUID.randomUUID().toString(),1,1);}
    UiFeedbackStore open()throws Exception{return UiFeedbackStore.open(root.resolve("runtime.db"),world,now::get,new UiFeedbackStore.Port(){
        public void authorizeSubmission(UiFeedbackStore.Scope scope,UiFeedbackStore.Document doc){checks++;if(!live||!standing||denyAtCommit&&checks>1)throw new SecurityException("FEEDBACK_NOT_AUTHORIZED");}
        public boolean standing(UiFeedbackStore.Scope scope){return standing;}
        public boolean documentCurrent(UiFeedbackStore.Scope scope,UiFeedbackStore.Document doc){return live;}
        public boolean verifyCompletion(UiFeedbackStore.Item item,UiFeedbackStore.Completion proof){return completion;}
    });}
    UiFeedbackStore.Item submit(UiFeedbackStore s,UiFeedbackStore.Scope scope,UUID op)throws Exception{return s.submit(scope,document(),op,"{\"question\":\"help\"}");}
    @Test void twoAuthorsStayDistinctAndDuplicateRequiresCurrentDocumentBeforeReceipt()throws Exception{
        try(var s=open()){
            var scope=scope("RECORD_ONLY","INDEPENDENT");UUID op=UUID.randomUUID();var item=submit(s,scope,op);assertEquals("ACCEPTED",item.state());assertFalse(item.exported());assertEquals(item,submit(s,scope,op));
            assertThrows(IllegalArgumentException.class,()->s.submit(scope,document(),op,"{\"question\":\"changed\"}"));
            assertThrows(SecurityException.class,()->s.inspect(UUID.randomUUID(),item.id()));assertEquals(conversation,s.inspect(author,item.id()).scope().conversationId());
            live=false;assertThrows(SecurityException.class,()->submit(s,scope,op));assertEquals(1,s.rows().size());
        }
    }
    @Test void spoofedScopeAndPayloadAreNotAcceptedByTheTrustedPort()throws Exception{
        try(var s=open()){standing=false;assertThrows(SecurityException.class,()->submit(s,scope("RECORD_ONLY","INDEPENDENT"),UUID.randomUUID()));assertTrue(s.rows().isEmpty());standing=true;assertThrows(IllegalArgumentException.class,()->s.submit(scope("RECORD_ONLY","INDEPENDENT"),document(),UUID.randomUUID(),"{\"question\":\"help\",\"playerId\":\"fake\"}"));assertTrue(s.rows().isEmpty());}
    }
    @Test void commitTimeRevocationRollsBackReceiptAndOutboxTogether()throws Exception{
        try(var s=open()){denyAtCommit=true;assertThrows(SecurityException.class,()->submit(s,scope("RECORD_ONLY","INDEPENDENT"),UUID.randomUUID()));assertTrue(s.rows().isEmpty());}
    }
    @Test void duplicateReceiptAlsoRechecksAuthorityAtTransactionEnd()throws Exception{
        try(var s=open()){var scope=scope("RECORD_ONLY","INDEPENDENT");UUID op=UUID.randomUUID();submit(s,scope,op);checks=0;denyAtCommit=true;assertThrows(SecurityException.class,()->submit(s,scope,op));assertEquals(1,s.rows().size());}
    }
    @Test void acceptedOutboxSurvivesRestartWithStableScopeAndNoDuplicateRecord()throws Exception{
        var scope=scope("RECORD_ONLY","INDEPENDENT");UUID op=UUID.randomUUID(),id;
        try(var s=open()){id=submit(s,scope,op).id();}
        try(var s=open()){var replay=submit(s,scope,op);assertEquals(id,replay.id());assertEquals(scope,replay.scope());assertEquals(1,s.pendingExports(4).size());new UiFeedbackOutbox(s,value->assertEquals(id,value.id())).pump(4);assertEquals("RECORDED",s.inspect(author,id).state());}
    }
    @Test void durableScopeSeparatelyRecordsPlayerSubjectsAndOriginIntent()throws Exception{
        var scope=scope("RECORD_ONLY","INDEPENDENT");assertEquals(author,scope.viewerId());assertEquals(author,scope.actorId());assertEquals("PLAYER",scope.actorKind());assertEquals(1,scope.originIntentRevision());
    }
    @Test void cooldownAndLifetimeQuotaDoNotCountIdenticalRetries()throws Exception{
        try(var s=open()){var scope=scope("RECORD_ONLY","INDEPENDENT");UUID op=UUID.randomUUID();var first=submit(s,scope,op);assertEquals(first,submit(s,scope,op));assertThrows(IllegalStateException.class,()->submit(s,scope,UUID.randomUUID()));
            for(int i=1;i<8;i++){now.addAndGet(1000);submit(s,scope,UUID.randomUUID());}now.addAndGet(1000);assertThrows(IllegalStateException.class,()->submit(s,scope,UUID.randomUUID()));assertEquals(8,s.rows().size());}
    }
    @Test void recordOnlyExportIsNotBusinessCompletionAndRetryUsesSameEvent()throws Exception{
        try(var s=open()){var item=submit(s,scope("RECORD_ONLY","INDEPENDENT"),UUID.randomUUID());Set<UUID> received=new HashSet<>();var outbox=new UiFeedbackOutbox(s,value->received.add(value.id()));assertEquals(1,outbox.pump(4));assertEquals(0,outbox.pump(4));assertEquals(Set.of(item.id()),received);var recorded=s.inspect(author,item.id());assertEquals("RECORDED",recorded.state());assertTrue(recorded.exported());assertThrows(IllegalStateException.class,()->s.processing(item.id(),UUID.randomUUID()));assertThrows(IllegalStateException.class,()->s.complete(item.id(),new UiFeedbackStore.Completion(UUID.randomUUID(),"TASK_GOAL_VERIFIED","{}")));}
    }
    @Test void exportFailureRetainsSameDurableEventAndDoesNotSilentlyDropIt()throws Exception{
        try(var s=open()){var item=submit(s,scope("RECORD_ONLY","INDEPENDENT"),UUID.randomUUID());Set<UUID> delivered=new HashSet<>();var failing=new UiFeedbackOutbox(s,value->{delivered.add(value.id());throw new IllegalStateException("ACK_LOST");});assertThrows(IllegalStateException.class,()->failing.pump(4));assertFalse(s.inspect(author,item.id()).exported());new UiFeedbackOutbox(s,value->delivered.add(value.id())).pump(4);assertEquals(1,delivered.size());assertTrue(s.inspect(author,item.id()).exported());}
    }
    @Test void pageBoundCloseCancelsButIndependentAcceptedWorkContinuesWithoutAView()throws Exception{
        try(var s=open()){var bound=submit(s,scope("AGENT_WAKE","PAGE_BOUND"),UUID.randomUUID());now.addAndGet(1000);var independent=submit(s,scope("AGENT_WAKE","INDEPENDENT"),UUID.randomUUID());live=false;s.reconcile();assertEquals("CANCELLED",s.inspect(author,bound.id()).state());assertEquals("ACCEPTED",s.inspect(author,independent.id()).state());s.exported(independent.id());s.processing(independent.id(),UUID.randomUUID());completion=false;assertThrows(SecurityException.class,()->s.complete(independent.id(),new UiFeedbackStore.Completion(UUID.randomUUID(),"TASK_GOAL_VERIFIED","{}")));assertEquals("PROCESSING",s.inspect(author,independent.id()).state());completion=true;s.complete(independent.id(),new UiFeedbackStore.Completion(UUID.randomUUID(),"TASK_GOAL_VERIFIED","{\"answer\":\"scoped\"}"));assertEquals("COMPLETED",s.inspect(author,independent.id()).state());assertEquals(conversation,s.inspect(author,independent.id()).scope().conversationId());}
    }
    @Test void revokeRegrantDoesNotReviveCancelledFeedbackAndProcessingRestartIsInterrupted()throws Exception{
        UUID first,second;
        try(var s=open()){first=submit(s,scope("AGENT_WAKE","INDEPENDENT"),UUID.randomUUID()).id();standing=false;s.reconcile();standing=true;s.reconcile();assertEquals("CANCELLED",s.inspect(author,first).state());now.addAndGet(1000);second=submit(s,scope("AGENT_WAKE","INDEPENDENT"),UUID.randomUUID()).id();s.exported(second);s.processing(second,UUID.randomUUID());}
        try(var s=open()){assertEquals("CANCELLED",s.inspect(author,first).state());assertEquals("INTERRUPTED",s.inspect(author,second).state());assertEquals("SERVER_RESTART_NO_REPLAY",s.inspect(author,second).error());assertTrue(s.pendingExports(4).isEmpty());}
    }
    @Test void activeStoreCannotBeRecoveredByAnotherOwnerAndUnacceptedRowsCannotComplete()throws Exception{
        try(var s=open()){var item=submit(s,scope("AGENT_WAKE","INDEPENDENT"),UUID.randomUUID());var failure=assertThrows(IllegalStateException.class,()->{try(var other=open()){fail("second feedback store acquired lock");}});assertEquals("FEEDBACK_STORE_ALREADY_OPEN",failure.getMessage());assertThrows(IllegalStateException.class,()->s.complete(item.id(),new UiFeedbackStore.Completion(UUID.randomUUID(),"TASK_GOAL_VERIFIED","{}")));assertEquals("ACCEPTED",s.inspect(author,item.id()).state());}
    }
    @Test void realEventStoreDeduplicatesBeforeAckAcrossReopenAndNeverContainsPayloadValues()throws Exception{
        var scope=scope("RECORD_ONLY","INDEPENDENT");UUID op=UUID.randomUUID(),id;
        var eventPort=new dev.mineagent.runtime.core.events.RuntimeEventStore.Port(){
            public void authorize(dev.mineagent.runtime.core.events.RuntimeEventStore.Context c){}
            public String authority(dev.mineagent.runtime.core.events.RuntimeEventStore.Subscription s){return "test";}
            public boolean matches(dev.mineagent.runtime.core.events.RuntimeEventStore.Subscription s,dev.mineagent.runtime.core.events.RuntimeEventStore.Event e){return false;}
        };
        try(var feedback=open();var events=dev.mineagent.runtime.core.events.RuntimeEventStore.open(root.resolve("runtime.db"),world,now::get,eventPort)){
            var item=feedback.submit(scope,document(),op,"{\"question\":\"PRIVATE_FEEDBACK_VALUE\"}");id=item.id();
            var event=dev.mineagent.runtime.core.events.RuntimeEventStore.Event.feedback(item);assertEquals(1,events.ingest(event));
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event).contains("PRIVATE_FEEDBACK_VALUE"));
            assertFalse(feedback.inspect(author,id).exported());
        }
        try(var feedback=open();var events=dev.mineagent.runtime.core.events.RuntimeEventStore.open(root.resolve("runtime.db"),world,now::get,eventPort)){
            assertEquals(1,new UiFeedbackOutbox(feedback,item->assertEquals(1,events.ingest(dev.mineagent.runtime.core.events.RuntimeEventStore.Event.feedback(item)))).pump(4));
            var event=events.event(id).orElseThrow();assertEquals("UI_FEEDBACK",event.source());assertEquals(author,event.author());assertEquals(conversation,event.feedback().scope().conversationId());
            assertEquals("RECORDED",feedback.inspect(author,id).state());assertEquals(0,new UiFeedbackOutbox(feedback,item->fail("re-exported acknowledged event")).pump(4));
        }
    }
    @Test void exactFeedbackMigrationPreservesReceiptAndConversationAcrossReopen()throws Exception{
        var scope=scope("RECORD_ONLY","INDEPENDENT");UUID operation=UUID.randomUUID(),itemId,conversationOperation;
        try(var store=open()){
            var item=submit(store,scope,operation);itemId=item.id();conversationOperation=store.conversationOperation(author,delivery);
            assertEquals(conversationOperation,store.conversationOperation(author,delivery));
            assertNotEquals(conversationOperation,store.conversationOperation(UUID.randomUUID(),delivery));
        }
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("runtime.db"));var update=db.prepareStatement("UPDATE mineagent_ui_feedback_v1 SET fingerprint=? WHERE world=? AND operation=?")){
            update.setString(1,"b".repeat(64));update.setString(2,world.toString());update.setString(3,operation.toString());assertEquals(1,update.executeUpdate());
        }
        try(var store=open()){
            assertEquals(itemId,submit(store,scope,operation).id());assertEquals(1,store.rows().size());
            assertEquals(conversation,store.existingConversation(author,delivery).orElseThrow());
            assertTrue(store.existingConversation(UUID.randomUUID(),delivery).isEmpty());
            assertEquals(conversationOperation,store.conversationOperation(author,delivery));
            assertThrows(IllegalArgumentException.class,()->store.submit(scope,document(),operation,"{\"question\":\"different\"}"));
        }
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("runtime.db"));var query=db.createStatement();var rows=query.executeQuery("SELECT fingerprint FROM mineagent_ui_feedback_v1")){
            assertTrue(rows.next());assertTrue(rows.getString(1).startsWith("exact-v2:"));
        }
    }
    @Test void equivalentCandidatesReuseReservedVersionButChangedTransactionCannotBorrowIt()throws Exception{
        try(var store=open()){
            var binding=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count"),Set.of("count"));
            var item=submit(store,scope("DETERMINISTIC","INDEPENDENT").withDataBinding(binding),UUID.randomUUID());
            UUID consumer=UUID.randomUUID();store.exported(item.id());store.processing(item.id(),consumer);
            String source="{\"transaction\":{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1}]}}";
            var first=FeedbackTransactionPlan.parse(binding,source);var second=FeedbackTransactionPlan.parse(binding,source);
            assertNotEquals(first.sha256(),second.sha256());store.reserveDataPlan(item.id(),consumer,first);
            assertEquals(first,store.reserveDataPlan(item.id(),consumer,second));
            var changed=FeedbackTransactionPlan.parse(binding,source.replace("value\":1","value\":2"));
            var borrowed=new FeedbackTransactionPlan(changed.transaction(),changed.canonical(),first.sha256());
            assertThrows(IllegalStateException.class,()->store.reserveDataPlan(item.id(),consumer,borrowed));
            assertEquals("PROCESSING",store.runtimeItem(item.id()).orElseThrow().state());
        }
    }

}
