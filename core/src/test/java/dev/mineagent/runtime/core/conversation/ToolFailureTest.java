package dev.mineagent.runtime.core.conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;
class ToolFailureTest {
    @Test void wrappedAsyncFailureKeepsRootReasonAndOperation(){
        var id=UUID.randomUUID();var r=ToolFailure.result("apply_building",id,new CompletionException(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN",new IllegalArgumentException("component roof: unsupported axis q"))),ToolFailure.Phase.DISPATCH);
        assertEquals("UNKNOWN",r.get("executionState"));assertEquals(id.toString(),r.get("operation_id"));assertEquals("component roof: unsupported axis q",r.get("diagnostic"));assertEquals(false,r.get("replayAllowed"));
    }
    @Test void malformedJsonReturnsLocationAndNotStarted(){
        var e=assertThrows(IllegalArgumentException.class,()->ToolArguments.parse("run_game_command","{\n\"command\": }"));
        var r=ToolFailure.result("run_game_command",UUID.randomUUID(),e,ToolFailure.Phase.VALIDATION);
        assertEquals("NOT_STARTED",r.get("executionState"));assertEquals(2,r.get("line"));assertTrue((int)r.get("column")>0);assertNotEquals("AGENT_TOOL_JSON_INVALID",r.get("diagnostic"));
    }
    @Test void persistenceConflictNeverClaimsPriorOperationDidNotExecute(){
        var r=ToolFailure.result("give_item",UUID.randomUUID(),new IllegalStateException("AGENT_TOOL_NOT_REPLAYABLE"),ToolFailure.Phase.INTENT_STORAGE);assertEquals("UNKNOWN",r.get("executionState"));
        assertFalse(ToolFailure.result("tool",UUID.randomUUID(),new IllegalArgumentException("apiKey=secret-value"),ToolFailure.Phase.VALIDATION).toString().contains("secret-value"));
    }
    @Test void unknownWriteBlocksEquivalentReplayButAllowsObservationAndCorrection(){
        var guard=new UncertainToolCalls();var id=UUID.randomUUID();guard.observe("give_item","{\"item\":\"minecraft:apple\",\"count\":1}",ToolFailure.result("give_item",id,new IllegalStateException("ACK_TIMEOUT"),ToolFailure.Phase.DISPATCH));
        assertTrue(guard.blocked("give_item","{\"count\":1,\"item\":\"minecraft:apple\"}").isPresent());assertTrue(guard.blocked("inspect_operations","{}").isEmpty());
        guard.observe("inspect_operations","{}",Map.of("operation",id,"nextOffset",-1,"text","{\"status\":\"UNKNOWN\"}"));assertTrue(guard.blocked("give_item","{\"item\":\"minecraft:apple\",\"count\":1}").isPresent());
        guard.observe("inspect_operations","{}",Map.of("operation",id,"nextOffset",-1,"text","{\"executionState\":\"NOT_STARTED\"}"));assertTrue(guard.blocked("give_item","{\"item\":\"minecraft:apple\",\"count\":1}").isEmpty());
    }
}
