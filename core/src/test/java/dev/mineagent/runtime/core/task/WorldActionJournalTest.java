package dev.mineagent.runtime.core.task;
import dev.mineagent.runtime.api.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WorldActionJournalTest {
    @TempDir Path dir;
    private ManagedTask task(UUID world){return new ManagedTask(UUID.randomUUID(),world,UUID.randomUUID(),UUID.randomUUID(),"动作",1,1,TaskStatus.RUNNING,List.of(new TaskStep("plan",Set.of(),TaskNodeStatus.PENDING),new TaskStep("execute",Set.of("plan"),TaskNodeStatus.PENDING)),"",0,1);}
    private List<WorldActionSpec> actions(){return List.of(new WorldActionSpec("move","move_to",2,80,0,"",""),new WorldActionSpec("break","break_block",3,80,0,"",""));}
    @Test void planIsValidatedCompletelyAndSequentialReceiptsNotDispatchMarkCompletion()throws Exception{
        UUID world=UUID.randomUUID();var task=task(world);
        try(var journal=WorldActionJournal.open(dir.resolve("db"),world)){
            var a=journal.accept(task,"minecraft:overworld",actions());assertEquals("READY",a.state());
            assertEquals(a,journal.accept(task,"minecraft:overworld",actions()));
            assertThrows(IllegalArgumentException.class,()->journal.accept(task,"minecraft:the_nether",actions()));
            var started=journal.begin(a.batchId(),Map.of("x","0"));assertEquals("EXECUTING",started.state());assertEquals(0,started.cursor());assertEquals(a.operationIds(),started.operationIds());
            var moved=journal.completeAction(a.batchId(),true,"",Map.of("arrived","true"));assertEquals("READY",moved.state());assertEquals(1,moved.cursor());assertEquals(a.operationId(0),moved.receipts().getFirst().operationId());assertNotEquals(started.operationId(),moved.operationId());
            journal.begin(a.batchId(),Map.of("block","minecraft:stone"));var done=journal.completeAction(a.batchId(),true,"",Map.of("nativeBreak","true"));
            assertEquals("VERIFIED",done.state());assertEquals(2,done.receipts().size());assertEquals(2,done.cursor());
            assertThrows(IllegalStateException.class,()->journal.begin(a.batchId(),Map.of()));
        }
    }
    @Test void failureStopsFollowingActionsAndRestartDoesNotReplayUnknownNativeSideEffects()throws Exception{
        UUID world=UUID.randomUUID();var task=task(world);UUID id;
        try(var journal=WorldActionJournal.open(dir.resolve("db"),world)){
            id=journal.accept(task,"minecraft:overworld",actions()).batchId();journal.begin(id,Map.of("before","state"));
        }
        try(var journal=WorldActionJournal.open(dir.resolve("db"),world)){
            var a=journal.forTask(task.taskId(),task.intentRevision()).orElseThrow();assertEquals("INTERRUPTED",a.state());assertEquals(0,a.cursor());
            assertThrows(IllegalStateException.class,()->journal.begin(id,Map.of()));
            var second=task(world);var b=journal.accept(second,"minecraft:overworld",actions());journal.begin(b.batchId(),Map.of());
            var failed=journal.completeAction(b.batchId(),false,"TARGET_CHANGED",Map.of());assertEquals("FAILED",failed.state());assertEquals(0,failed.cursor());
            assertThrows(IllegalStateException.class,()->journal.begin(b.batchId(),Map.of()));
        }
    }
    @Test void rejectsUnknownToolsBadCoordinatesAndDuplicateCallIdsBeforeAdmission(){
        assertThrows(IllegalArgumentException.class,()->new WorldActionSpec("id","exec",0,0,0,"",""));
        assertThrows(IllegalArgumentException.class,()->WorldActionSpec.parse(List.of(Map.of("id","x","name","move_to","arguments","{\"x\":1.5,\"y\":80,\"z\":0}"))));
        assertThrows(IllegalArgumentException.class,()->WorldActionSpec.parse(List.of(Map.of("id","x","name","move_to","arguments","{\"x\":1,\"x\":2,\"y\":80,\"z\":0}"))));
        assertThrows(IllegalArgumentException.class,()->WorldActionSpec.parse(List.of(Map.of("id","x","name","say","arguments","{\"message\":\"ok\"}"),Map.of("id","x","name","say","arguments","{\"message\":\"again\"}"))));
        assertThrows(IllegalArgumentException.class,()->WorldActionSpec.parse(List.of(Map.of("name","say","arguments","{\"message\":\"missing id\"}"))));
    }
    @Test void laterPlanningRoundsKeepEarlierReceiptsAndUncertainPlanningDoesNotReplay()throws Exception{
        var world=UUID.randomUUID();var task=task(world);
        try(var j=WorldActionJournal.open(dir.resolve("db"),world)){
            j.beginPlanning(task);j.endPlanning(task.taskId(),task.intentRevision(),true);
            var first=j.accept(task,"minecraft:overworld",actions(),0);j.begin(first.batchId(),Map.of());j.completeAction(first.batchId(),true,"",Map.of());j.begin(first.batchId(),Map.of());j.completeAction(first.batchId(),true,"",Map.of());j.completed(first.batchId());
            var next=j.accept(task,"minecraft:overworld",List.of(new WorldActionSpec("next","say",0,0,0,"","done")),1);assertEquals(1,next.round());assertEquals(2,j.all().size());
            assertEquals(1,j.forTask(task.taskId(),task.intentRevision()).orElseThrow().round());j.beginPlanning(task);
        }
        try(var j=WorldActionJournal.open(dir.resolve("db"),world)){assertThrows(IllegalStateException.class,()->j.beginPlanning(task));}
    }
}
