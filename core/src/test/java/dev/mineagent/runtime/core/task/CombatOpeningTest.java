package dev.mineagent.runtime.core.task;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CombatOpeningTest {
    @Test void closingMustFitRealWindowAndOwnCooldown() {
        int closing = CombatOpening.closingTicks(6, 3, .28);
        assertTrue(CombatOpening.canCounter(20, closing, 0, false));
        assertFalse(CombatOpening.canCounter(8, closing, 0, false));
        assertFalse(CombatOpening.canCounter(20, closing, 18, false));
        assertFalse(CombatOpening.canCounter(20, closing, 0, true));
    }

    @Test void allRunningAttacksMatterAndSnapshotsAge() {
        var attacks = List.of(new CombatOpening.Attack("MELEE", 20, true), new CombatOpening.Attack("RANGED", 6, true));
        assertEquals(3, CombatOpening.availableTicks(attacks, List.of(), 3));
        assertEquals(0, CombatOpening.availableTicks(attacks, List.of(), 10));
    }

    @Test void unknownOrReadyAttackIsNotInventedStun() {
        assertEquals(0, CombatOpening.availableTicks(List.of(new CombatOpening.Attack("MELEE", -1, true)), List.of(), 0));
        assertEquals(0, CombatOpening.availableTicks(List.of(), List.of(), 0));
        assertEquals(0, CombatOpening.availableTicks(List.of(new CombatOpening.Attack("AREA", 0, true)), List.of(new CombatOpening.Restriction(40, true, true)), 0));
    }

    @Test void realMeleeStunDoesNotDisableOtherAttackKinds() {
        var stun = List.of(new CombatOpening.Restriction(40, true, false));
        assertEquals(40, CombatOpening.availableTicks(List.of(new CombatOpening.Attack("MELEE", 0, true)), stun, 0));
        assertEquals(0, CombatOpening.availableTicks(List.of(new CombatOpening.Attack("MELEE", 0, true), new CombatOpening.Attack("RANGED", 0, true)), stun, 0));
    }
}
