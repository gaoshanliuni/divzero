package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SkillAdjustmentTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void areaChangePreservesIdentityCombatAndActualCompletedCounters()throws Exception{
        var spec=SkillSpec.parse(json.readTree("{\"id\":\"field\",\"kind\":\"FARM\",\"dimension\":\"minecraft:overworld\",\"min\":[0,64,0],\"max\":[5,64,5]}"),null);
        var session=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,spec);session.cursor(15);session.add("planted",8);
        var adjusted=spec.adjust(json.readTree("{\"max\":[8,64,5],\"limit\":12}"));session.adjust(1,adjusted);
        assertEquals(8,session.count("planted"));assertEquals(0,session.cursor());assertEquals(2,session.revision());assertEquals(spec.combat(),session.spec().combat());assertEquals("field",session.spec().id());
        assertThrows(IllegalStateException.class,()->session.adjust(1,adjusted));
        assertThrows(IllegalArgumentException.class,()->spec.adjust(json.readTree("{\"actor\":\"player\"}")));
        session.receipt(Map.of("state","PREPARED","operation",UUID.randomUUID().toString()));assertThrows(IllegalStateException.class,()->session.adjust(2,adjusted));
    }
    @Test void suspendedPreviousWorkKeepsItsResumeRelationship()throws Exception{
        var spec=SkillSpec.parse(json.readTree("{\"id\":\"idle\",\"kind\":\"IDLE\",\"dimension\":\"minecraft:overworld\"}"),null);
        var session=new SkillSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,spec);session.transition(SkillSession.State.PAUSED,"TEMPORARY_WORK");session.adjust(1,spec.adjust(json.readTree("{\"dwell_ticks\":30}")));
        assertEquals(SkillSession.State.PAUSED,session.state());assertEquals("TEMPORARY_WORK",session.reason());
    }
}
