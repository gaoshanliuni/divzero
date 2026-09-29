package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CombatBoundsTest {
    @Test void canDefendAreaCornersAndReenterButCannotExpandAnUnassignedPursuit(){
        assertTrue(CombatBounds.canAdvance(true,55,53,40));
        assertTrue(CombatBounds.canAdvance(false,48,50,40));
        assertFalse(CombatBounds.canAdvance(false,51,50,40));
        assertFalse(CombatBounds.canAdvance(false,50,50,40));
        assertFalse(CombatBounds.canAdvance(false,41,39,40));
        assertTrue(CombatBounds.canAdvance(false,39,40,40));
    }
}
