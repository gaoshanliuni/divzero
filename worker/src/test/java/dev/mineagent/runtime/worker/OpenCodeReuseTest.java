package dev.mineagent.runtime.worker;

import dev.mineagent.runtime.scripting.opencode.OpenCodeRuntime;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeReuseTest {
    @Test void actuallyExecutesVendoredSkillAndAnchoredSummaryFunctions(){
        String loaded=OpenCodeRuntime.skill("building","Inspect, plan, apply, verify.","divzero:skills/building",List.of("divzero:skills/building/format"));
        assertTrue(loaded.contains("<skill_content name=\"building\">"));
        assertTrue(loaded.contains("<file>divzero:skills/building/format</file>"));
        String prompt=OpenCodeRuntime.summaryPrompt("operation=a, revision=4, UNKNOWN",List.of("Still awaiting verified receipt."));
        assertTrue(prompt.contains("<prior-summary>"));
        assertTrue(prompt.contains("revision=4, UNKNOWN"));
        assertTrue(prompt.contains("### Blocked"));
    }
    @Test void KeepsRecentAtomicGroupsAndRecognizesOnlyMatchingNonPendingCalls(){
        var selected=OpenCodeRuntime.select(List.of("old group ".repeat(100),"new call + required reasoning + result"),15);
        assertTrue(selected.archived().startsWith("old group"),selected::toString);
        assertEquals("new call + required reasoning + result",selected.recent());
        Map<String,Object> args=Map.of("id","house","revision",4);
        Map<String,Object> part=Map.of("type","tool","tool","verify_building","state",Map.of("status","error","input",args));
        assertTrue(OpenCodeRuntime.repeatedCalls(List.of(part,part,part),"verify_building",args));
        assertFalse(OpenCodeRuntime.repeatedCalls(List.of(part,part),"verify_building",args));
        assertFalse(OpenCodeRuntime.repeatedCalls(List.of(part,part,part),"apply_building",args));
    }
}
