package dev.mineagent.runtime.core.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanDuelSeriesTest {
    @Test void fiveHumanReadyRoundsWithDeathAndExactWallClockLimit() {
        var s = new HumanDuelSeries();
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, s.round()); assertTrue(s.ready(0)); assertFalse(s.ready(0));
            assertFalse(s.countdownComplete(4_999_999_999L)); assertTrue(s.countdownComplete(5_000_000_000L));
            s.starting(); s.started(10);
            assertEquals("", s.outcome(HumanDuelSeries.LIMIT + 9, true, true));
            assertEquals("TIME_LIMIT_DRAW", s.outcome(HumanDuelSeries.LIMIT + 10, true, true));
            assertEquals("AI_WON", s.outcome(11, false, true));
            assertEquals("HUMAN_WON", s.outcome(11, true, false));
            assertTrue(s.finish()); assertFalse(s.finish());
            assertEquals(i == 4 ? HumanDuelSeries.Phase.COMPLETE : HumanDuelSeries.Phase.BETWEEN, s.phase());
        }
        assertFalse(s.ready(0));
    }
    @Test void stopDoesNotCountPartialRoundOrAllowLateRestart() {
        var s = new HumanDuelSeries(); s.ready(0); s.starting(); s.stop();
        assertThrows(IllegalStateException.class, () -> s.started(0));
        assertEquals(0, s.completed()); assertFalse(s.ready(0)); assertFalse(s.finish());
    }
}
