package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CombatSourcePriorityTest {
    @Test void sourcePressureUsesObservedDependentsAndCastTimeWithoutUnboundedChasing() {
        assertEquals(0, CombatSourcePriority.bonus(4,0,false,0));
        assertTrue(CombatSourcePriority.bonus(8,3,true,10)>CombatSourcePriority.bonus(8,0,true,200));
        assertTrue(CombatSourcePriority.bonus(8,3,true,10)>CombatSourcePriority.bonus(20,3,true,10));
        assertEquals(0, CombatSourcePriority.bonus(33,100,true,0));
        assertTrue(CombatSourcePriority.bonus(0,100,true,0)<18); // Emergency protected-target weight retains priority.
    }
}
