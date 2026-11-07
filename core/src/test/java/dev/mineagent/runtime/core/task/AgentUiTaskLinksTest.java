package dev.mineagent.runtime.core.task;
import dev.mineagent.runtime.api.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentUiTaskLinksTest {
    @TempDir Path dir;final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID();TaskManager tasks;AgentUiTaskLinks links;
    @BeforeEach void open()throws Exception{tasks=TaskManager.open(dir.resolve("runtime.db"),world,Clock.systemUTC());links=AgentUiTaskLinks.open(dir.resolve("runtime.db"),world,Clock.systemUTC(),tasks);}
    @AfterEach void close()throws Exception{links.close();tasks.close();}
    ManagedTask parent()throws Exception{return tasks.create(agent,owner,"生成独立网页",1,List.of(new TaskStepSpec("plan",Set.of()),new TaskStepSpec("execute",Set.of("plan"))));}
    AgentUiTaskLinks.Request create(){return AgentUiTaskLinks.Request.parse("create_ui_package","{\"prompt\":\"生成没有对应成品的独立表单\"}");}
    @Test void oneStableChildOperationWaitsForActualPublicationAndCompletesOnce()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());assertEquals(link.operationId(),links.prepare(parent,create()).operationId());
        links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);
        assertEquals("WAITING",links.observe(link.operationId(),"GENERATING",true).state());assertNotEquals(TaskStatus.COMPLETED,tasks.get(parent.taskId()).orElseThrow().status());
        links.observe(link.operationId(),"PUBLISHED",true);var completed=tasks.get(parent.taskId()).orElseThrow();assertEquals(TaskStatus.COMPLETED,completed.status());
        links.observe(link.operationId(),"PUBLISHED",true);assertEquals(completed.revision(),tasks.get(parent.taskId()).orElseThrow().revision());
    }
    @Test void aPatchProposalWaitsForExplicitApplyRatherThanClaimingTheEditIsDone()throws Exception{
        var parent=parent();var req=AgentUiTaskLinks.Request.parse("propose_ui_patch","{\"package_id\":\""+UUID.randomUUID()+"\",\"base_revision\":3,\"prompt\":\"调整间距\"}");
        var link=links.prepare(parent,req);links.attached(link.operationId(),UUID.randomUUID(),req.packageId(),4);
        assertEquals("WAITING_APPLY",links.observe(link.operationId(),"READY",true).state());
        assertEquals(TaskNodeStatus.WAITING_FOR_PLAYER,tasks.get(parent.taskId()).orElseThrow().steps().stream().filter(s->s.stepId().equals("execute")).findFirst().orElseThrow().status());
        links.observe(link.operationId(),"APPLIED",true);assertEquals(TaskStatus.COMPLETED,tasks.get(parent.taskId()).orElseThrow().status());
    }
    @Test void cancelledOrChangedParentCannotPublishLateChildResult()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);
        var live=tasks.get(parent.taskId()).orElseThrow();tasks.transition(live.taskId(),live.revision(),true,TaskStatus.CANCELLED);
        assertFalse(links.mayProduce(link.operationId()));assertEquals("STALE",links.observe(link.operationId(),"PUBLISHED",true).state());
        assertEquals(TaskStatus.CANCELLED,tasks.get(parent.taskId()).orElseThrow().status());
    }
    @Test void restartPausesParentsAndNeverRequiresASecondProviderSubmission()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);
        links.close();links=AgentUiTaskLinks.open(dir.resolve("runtime.db"),world,Clock.systemUTC(),tasks);
        assertEquals(TaskStatus.PAUSED,tasks.get(parent.taskId()).orElseThrow().status());assertEquals("INTERRUPTED",links.operation(link.operationId()).orElseThrow().state());
        var live=tasks.get(parent.taskId()).orElseThrow();tasks.transition(live.taskId(),live.revision(),true,TaskStatus.RUNNING);
        links.observe(link.operationId(),"PUBLISHED",true);assertEquals(TaskStatus.COMPLETED,tasks.get(parent.taskId()).orElseThrow().status());
    }
    @Test void strictToolArgumentsAndSameIntentReuseAreValidated()throws Exception{
        assertThrows(IllegalArgumentException.class,()->AgentUiTaskLinks.Request.parse("ui_apply","{}"));
        assertThrows(IllegalArgumentException.class,()->AgentUiTaskLinks.Request.parse("create_ui_package","{\"prompt\":\"x\",\"script\":\"eval\"}"));
        assertThrows(IllegalArgumentException.class,()->AgentUiTaskLinks.Request.parse("propose_ui_patch","{\"package_id\":\""+UUID.randomUUID()+"\",\"base_revision\":1.5,\"prompt\":\"x\"}"));
        var parent=parent();links.prepare(parent,create());
        assertThrows(IllegalArgumentException.class,()->links.prepare(parent,new AgentUiTaskLinks.Request("create_ui_package","different",null,0)));
    }
    @Test void revokedPermissionOrChildFailurePausesParentInsteadOfReportingSuccess()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);
        assertEquals("FAILED",links.observe(link.operationId(),"PUBLISHED",false).state());assertEquals(TaskStatus.PAUSED,tasks.get(parent.taskId()).orElseThrow().status());
    }
    @Test void resumingATerminalFailureDoesNotLeaveAnUnrunnableTaskOrRebillItsChild()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());links.observe(link.operationId(),"DISPATCH_FAILED",true);
        var paused=tasks.get(parent.taskId()).orElseThrow();tasks.transition(paused.taskId(),paused.revision(),true,TaskStatus.RUNNING);
        assertTrue(links.holdFailedResume(link.operationId()));assertEquals(TaskStatus.PAUSED,tasks.get(parent.taskId()).orElseThrow().status());assertFalse(links.mayProduce(link.operationId()));
        assertFalse(links.holdFailedResume(link.operationId()));
    }
    @Test void uiScopedPlanningNodeCompletesThroughTheSameJournal()throws Exception{
        var parent=tasks.create(agent,owner,"网页任务",1,List.of(new TaskStepSpec("plan_ui",Set.of()),new TaskStepSpec("execute",Set.of("plan_ui"))));
        var link=links.prepare(parent,create());links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);
        links.observe(link.operationId(),"PUBLISHED",true);assertEquals(TaskStatus.COMPLETED,tasks.get(parent.taskId()).orElseThrow().status());
    }
    @Test void crashAfterParentCompletionCanReconcileKnownPublishedChildWithoutReexecutingSteps()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);
        var live=tasks.get(parent.taskId()).orElseThrow();tasks.completeStep(live.taskId(),live.revision(),"execute");
        links.close();links=AgentUiTaskLinks.open(dir.resolve("runtime.db"),world,Clock.systemUTC(),tasks);
        long revision=tasks.get(parent.taskId()).orElseThrow().revision();assertEquals("COMPLETED",links.observe(link.operationId(),"PUBLISHED",true).state());
        assertEquals(revision,tasks.get(parent.taskId()).orElseThrow().revision());
    }
    @Test void completedChildIsFoundByOriginalParentWithoutDerivingItsOperation()throws Exception{
        var parent=parent();var link=links.prepare(parent,create());links.attached(link.operationId(),UUID.randomUUID(),UUID.randomUUID(),1);links.observe(link.operationId(),"PUBLISHED",true);
        links.close();links=AgentUiTaskLinks.open(dir.resolve("runtime.db"),world,Clock.systemUTC(),tasks);
        assertEquals(link.operationId(),links.forTask(parent.taskId(),parent.intentRevision()).orElseThrow().operationId());
        assertEquals(link.operationId(),links.prepare(parent,create()).operationId());assertEquals("COMPLETED",links.operation(link.operationId()).orElseThrow().state());
        assertTrue(links.forTask(parent.taskId(),parent.intentRevision()+1).isEmpty());assertTrue(links.forTask(UUID.randomUUID(),parent.intentRevision()).isEmpty());
    }

}
