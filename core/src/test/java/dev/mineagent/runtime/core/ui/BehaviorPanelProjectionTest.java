package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.ui.UiProtocol;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BehaviorPanelProjectionTest {
    public static class WorldOnly {public String getWorld(){throw new AssertionError("World internals must not be serialized");}}
    private Map<String,Object> row(String actor,String state){return Map.of("session",Map.of("state",state,"reason","DEFENDING_THEN_RESUME","spec",Map.of("id","task_"+actor,"actor",actor,"kind","FOLLOW","combat",Map.of("strategy","AUTO")),"counters",Map.of("verifiedHits",7)),"actor",new WorldOnly(),"combat",Map.of("targetName","僵尸","attackTimeline",new WorldOnly()),"tactic","DAMAGE_EVASION");}
    @Test void combatHistoryAndOpaqueWorldObjectsCannotBreakPanelReceipts()throws Exception{
        var rows=new ArrayList<Object>();for(int i=0;i<100;i++)rows.add(row("ai","COMPLETED"));rows.add(row("ai","SUSPENDED"));rows.add(row("player","RUNNING"));
        var json=new ObjectMapper().writeValueAsString(BehaviorPanelProjection.snapshot(Map.of("skills",rows)));
        assertTrue(json.contains("verifiedHits"));assertTrue(json.contains("task_ai"));assertTrue(json.contains("task_player"));assertFalse(json.contains("attackTimeline"));
        assertDoesNotThrow(()->new UiProtocol.Receipt(UUID.randomUUID(),UiProtocol.Code.OBSERVED,Map.of("state",json)));
        var receipt=BehaviorPanelProjection.receipt(Map.of("status","APPLIED","skill",new WorldOnly(),"settings",Map.of("boost",true)));
        assertEquals("APPLIED",receipt.get("status"));assertTrue(new ObjectMapper().writeValueAsString(receipt).contains("boost"));
    }
}
