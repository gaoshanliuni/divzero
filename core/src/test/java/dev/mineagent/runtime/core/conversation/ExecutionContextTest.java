package dev.mineagent.runtime.core.conversation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {
    @Test void toolGroupsPreserveRequiredReasoningAndAllParallelResults(){
        Map<String,Object> assistant=Map.of("role","assistant","reasoning_content","provider-required opaque reasoning","tool_calls",List.of(Map.of("id","a"),Map.of("id","b")));
        Map<String,Object> a=Map.of("role","tool","tool_call_id","a","content","a-result");
        Map<String,Object> b=Map.of("role","tool","tool_call_id","b","content","b-result");
        var history=List.of(Map.<String,Object>of("role","user","content","goal"),assistant,a,b);
        var groups=ExecutionContext.groups(history);assertEquals(2,groups.size());assertEquals(List.of(assistant,a,b),groups.getLast());
        assertThrows(IllegalArgumentException.class,()->ExecutionContext.groups(List.of(assistant,a)));
    }
    @Test void largeObservationRetainsReceiptVersionFailureAndUnknownWithoutInventingSuccess(){
        UUID id=UUID.randomUUID();var result=ExecutionContext.observation(Map.of("data","x".repeat(20000),"operation",id.toString(),"target",Map.of("id","house","revision",9,"status","UNVERIFIED"),"status","UNKNOWN","error","WRITE_RECEIPT_MISSING"),id);
        assertEquals(true,result.get("detailsArchived"));assertEquals(id.toString(),result.get("record_id"));
        var critical=(Map<?,?>)result.get("critical");assertEquals(9,critical.get("$.target.revision"));assertEquals("UNVERIFIED",critical.get("$.target.status"));assertEquals("UNKNOWN",critical.get("$.status"));assertEquals("WRITE_RECEIPT_MISSING",critical.get("$.error"));
    }
    @Test void onlyExplicitPortableReadsMayRunConcurrently(){
        assertTrue(ToolExecutionTraits.of("read_file").parallelRead());assertTrue(ToolExecutionTraits.of("web_search").dimensionIndependent());
        assertFalse(ToolExecutionTraits.of("apply_building").dimensionIndependent());assertFalse(ToolExecutionTraits.of("quick_move_container").parallelRead());
        assertTrue(ToolExecutionTraits.of("combat_entity").body());assertTrue(ToolExecutionTraits.of("stop_actions").dimensionIndependent());
    }
}
