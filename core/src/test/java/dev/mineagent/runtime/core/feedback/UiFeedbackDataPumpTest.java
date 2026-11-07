package dev.mineagent.runtime.core.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiFeedbackDataPumpTest {
    @TempDir Path root;
    final UUID world=UUID.randomUUID(),author=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID();
    boolean authorized=true;int planned,applied;long now=1000;
    final FeedbackDataBinding binding=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count"),Set.of("count"));
    UiFeedbackStore open()throws Exception{return UiFeedbackStore.open(root.resolve("runtime.db"),world,()->now,new UiFeedbackStore.Port(){
        public void authorizeSubmission(UiFeedbackStore.Scope s,UiFeedbackStore.Document d){if(!authorized)throw new SecurityException();}
        public boolean standing(UiFeedbackStore.Scope s){return authorized;}
        public boolean documentCurrent(UiFeedbackStore.Scope s,UiFeedbackStore.Document d){return authorized;}
        public boolean verifyCompletion(UiFeedbackStore.Item i,UiFeedbackStore.Completion p){return authorized&&p.operationId().equals(i.id())&&p.code().equals("SHARED_TRANSACTION_APPLIED");}
    });}
    UiFeedbackStore.Item submit(UiFeedbackStore s)throws Exception{
        var policy=UiFeedbackPolicy.parse(UiFeedbackPolicyTest.declaration("DETERMINISTIC","INDEPENDENT").getBytes(java.nio.charset.StandardCharsets.UTF_8),"ui/index.html");
        var scope=new UiFeedbackStore.Scope(world,UUID.randomUUID(),author,author,author,"PLAYER",owner,agent,UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),1,"a".repeat(64),"ui/index.html",policy.sha256(),"help",policy.event("help"),"grant",UUID.randomUUID(),List.of()).withDataBinding(binding);
        UUID lease=UUID.randomUUID();var i=s.submit(scope,new UiFeedbackStore.Document(lease,UUID.randomUUID(),"delivery-"+lease,UUID.randomUUID().toString(),1,1),UUID.randomUUID(),"{\"question\":\"bounded\"}");s.exported(i.id());return i;
    }
    UiFeedbackDataPump pump(UiFeedbackStore s){return new UiFeedbackDataPump(s,new UiFeedbackDataPump.Port(){
        public FeedbackTransactionPlan plan(UiFeedbackStore.Item i){planned++;return FeedbackTransactionPlan.parse(binding,"{\"transaction\":{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1}]}}");}
        public UiFeedbackStore.Completion apply(UiFeedbackStore.Item i){assertNotNull(i.dataPlan());applied++;return new UiFeedbackStore.Completion(i.id(),"SHARED_TRANSACTION_APPLIED","{}");}
    });}
    @Test void boundedStagesPersistPlanBeforeApplyAndNeverRunOneItemTwicePerPump()throws Exception{
        try(var s=open()){var i=submit(s);var pump=pump(s);
            assertEquals(1,pump.pump(4));assertEquals("PROCESSING",s.inspect(author,i.id()).state());assertEquals(0,planned);
            pump.pump(4);assertEquals(1,planned);assertEquals(0,applied);assertNotNull(s.inspect(author,i.id()).dataPlan());
            pump.pump(4);assertEquals(1,applied);assertNull(s.inspect(author,i.id()).completion());assertEquals("VERIFY",pump.phases().get(i.id()));
            pump.pump(4);assertEquals("COMPLETED",s.inspect(author,i.id()).state());assertTrue(pump.phases().isEmpty());assertEquals(0,pump.pump(4));assertEquals(1,planned);assertEquals(1,applied);
        }
    }
    @Test void restartAtPlanOrActualApplyBoundaryDoesNotRepeatUnknownWork()throws Exception{
        for(boolean afterApply:List.of(false,true)){
            UUID id;try(var s=open()){id=submit(s).id();var pump=pump(s);pump.pump(4);pump.pump(4);if(afterApply)pump.pump(4);}
            int beforePlans=planned,beforeApplies=applied;
            try(var s=open()){assertEquals("INTERRUPTED",s.inspect(author,id).state());assertNotNull(s.inspect(author,id).dataPlan());assertNull(s.inspect(author,id).completion());var pump=pump(s);for(int n=0;n<4;n++)assertEquals(0,pump.pump(4));assertEquals(beforePlans,planned);assertEquals(beforeApplies,applied);}
        }
    }
    @Test void revokeAndRegrantBetweenStagesDoesNotApplyAReservedPlan()throws Exception{
        try(var s=open()){var i=submit(s);var pump=pump(s);pump.pump(4);pump.pump(4);authorized=false;pump.pump(4);authorized=true;pump.pump(4);assertEquals("CANCELLED",s.inspect(author,i.id()).state());assertEquals(1,planned);assertEquals(0,applied);assertTrue(pump.phases().isEmpty());}
    }
    @Test void activeQueueAndPerPumpBudgetStayBounded()throws Exception{
        try(var s=open()){for(int n=0;n<8;n++)submit(s);var pump=pump(s);assertThrows(IllegalArgumentException.class,()->pump.pump(0));assertThrows(IllegalArgumentException.class,()->pump.pump(5));
            for(int n=0;n<40;n++){assertTrue(pump.pump(2)<=2);assertTrue(pump.phases().size()<=4);}
            assertEquals(8,planned);assertEquals(8,applied);assertEquals(8,s.rows().stream().filter(i->i.state().equals("COMPLETED")).count());
        }
    }
    @Test void revocationAfterApplyNeverRepeatsOrPretendsToRollBackTheNativeEffect()throws Exception{
        try(var s=open()){var i=submit(s);var pump=pump(s);pump.pump(4);pump.pump(4);pump.pump(4);assertEquals(1,applied);authorized=false;pump.pump(4);authorized=true;for(int n=0;n<3;n++)pump.pump(4);assertEquals(1,applied);assertEquals("CANCELLED",s.inspect(author,i.id()).state());assertNull(s.inspect(author,i.id()).completion());}
    }
    @Test void applyExceptionIsUnknownAndCannotBeAutomaticallyRetried()throws Exception{
        try(var s=open()){var item=submit(s);int[] effects={0};var source=pump(s);source.pump(4);source.pump(4);
            // A consumer cannot adopt PROCESSING work from a discarded in-memory queue.
            var replacement=pump(s);assertEquals(0,replacement.pump(4));assertEquals(0,applied);
            // Explicitly use a second accepted item to exercise a real failing apply stage.
            var next=submit(s);var failing=new UiFeedbackDataPump(s,new UiFeedbackDataPump.Port(){
                public FeedbackTransactionPlan plan(UiFeedbackStore.Item i){return sPlan();}
                public UiFeedbackStore.Completion apply(UiFeedbackStore.Item i){effects[0]++;throw new IllegalStateException("NATIVE_OUTCOME_UNKNOWN");}
                private FeedbackTransactionPlan sPlan(){return FeedbackTransactionPlan.parse(binding,"{\"transaction\":{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":1}]}}");}
            });
            for(int n=0;n<6;n++)failing.pump(4);assertEquals(1,effects[0]);assertEquals("INTERRUPTED",s.inspect(author,next.id()).state());assertNull(s.inspect(author,next.id()).completion());assertNotNull(s.inspect(author,next.id()).dataPlan());assertEquals("PROCESSING",s.inspect(author,item.id()).state());
        }
    }
}
