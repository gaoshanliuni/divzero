package dev.mineagent.runtime.core.host;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HostExecutionOutcomeTest {
    @Test void cancellationDuringPreparationKeepsTheRealReasonAndNoProcessClaim(){
        var v=HostExecutionOutcome.describe(UUID.randomUUID(),Map.of("status","REJECTED","error","HOST_CONTEXT_CHANGED"),false,"PREPARING_ENVIRONMENT","HOST_CONVERSATION_CANCELLED");
        assertEquals("HOST_CONVERSATION_CANCELLED",v.get("error"));assertEquals("NOT_STARTED",v.get("executionState"));assertEquals(false,v.get("operationProcessStarted"));
    }
    @Test void cancellationAfterLaunchCannotBecomeSafeToReplay(){
        var v=HostExecutionOutcome.describe(UUID.randomUUID(),Map.of("status","CANCELLED","pid",42),true,"RUNNING","HOST_USER_CANCELLED");
        assertEquals("STARTED",v.get("executionState"));assertEquals(true,v.get("sideEffectsMayPersist"));assertEquals("HOST_USER_CANCELLED",v.get("error"));
    }
    @Test void lateCancelDoesNotEraseAConfirmedResult(){
        var v=HostExecutionOutcome.describe(UUID.randomUUID(),Map.of("status","EXECUTED","exitCode",0),true,"COMPLETE","HOST_USER_CANCELLED");
        assertEquals("EXECUTED",v.get("status"));assertEquals("COMPLETED",v.get("executionState"));assertFalse(v.containsKey("error"));
    }
    @Test void duplicateRefusalRetainsUncertaintyAboutTheOriginalExecution(){
        var v=HostExecutionOutcome.describe(UUID.randomUUID(),Map.of("status","REJECTED","error","HOST_OPERATION_NOT_REPLAYABLE"),false,"PREPARING_ENVIRONMENT","");
        assertEquals("UNKNOWN",v.get("executionState"));assertFalse(v.containsKey("operationProcessStarted"));assertEquals(true,v.get("sideEffectsMayPersist"));
    }
    @Test void cancellationGuidanceDoesNotTellTheModelToReplayOrChangeParameters(){
        var v=dev.mineagent.runtime.core.conversation.ToolErrors.explain(HostExecutionOutcome.describe(UUID.randomUUID(),Map.of("status","REJECTED","error","HOST_CONTEXT_CHANGED"),false,"DOWNLOADING_RUNTIME","HOST_CONVERSATION_CANCELLED"));
        assertEquals("CANCELLED_CONTEXT",v.get("category"));assertFalse(v.containsKey("suggestedAction"));assertEquals("HOST_CONVERSATION_CANCELLED",v.get("cancellationReason"));
    }
}
