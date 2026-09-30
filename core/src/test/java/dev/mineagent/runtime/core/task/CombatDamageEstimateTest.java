package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CombatDamageEstimateTest {
    @Test void armorChangesWhetherARealisticExitHitWouldBeLethal(){
        assertEquals(6,CombatDamageEstimate.afterArmor(6,0,0),1e-9);
        assertEquals(3.12,CombatDamageEstimate.afterArmor(6,15,0),1e-9);
        assertTrue(CombatDamageEstimate.afterArmor(6,15,0)<5);
        assertTrue(CombatDamageEstimate.afterArmor(30,15,0)>20);
    }
    @Test void toughnessAndKnownNativeCritAreAppliedBeforeArmor(){
        assertTrue(CombatDamageEstimate.afterArmor(9,20,8)<CombatDamageEstimate.afterArmor(9,20,0));
        assertEquals(5.22,CombatDamageEstimate.afterArmor(9,15,0),1e-9);
        assertThrows(IllegalArgumentException.class,()->CombatDamageEstimate.afterArmor(Double.NaN,15,0));
    }
}
