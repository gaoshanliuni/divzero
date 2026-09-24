package dev.mineagent.runtime.core.scoreboard;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScoreboardReadGuardTest {
    @Test
    void failingReadCannotEscapeTickAndRecoveryClearsFailure() {
        var gate = new ScoreboardReadGuard();
        var reads = new AtomicInteger();
        var errors = new AtomicInteger();
        Runnable bad = () -> {
            reads.incrementAndGet();
            throw new IllegalArgumentException("invalid native snapshot");
        };
        assertFalse(gate.run(10, bad, e -> errors.incrementAndGet()));
        assertTrue(gate.unavailable());
        assertFalse(gate.run(20, bad, e -> errors.incrementAndGet()));
        assertEquals(1, reads.get());
        assertFalse(gate.run(110, bad, e -> errors.incrementAndGet()));
        assertEquals(1, errors.get());
        assertTrue(gate.run(210, reads::incrementAndGet, e -> fail()));
        assertFalse(gate.unavailable());
        assertNull(gate.lastFailure());
        assertFalse(gate.run(220, bad, e -> errors.incrementAndGet()));
        assertEquals(2, errors.get());
    }

    @Test
    void continuousFailureReportsOncePer1200Ticks() {
        var gate = new ScoreboardReadGuard();
        var errors = new AtomicInteger();
        Runnable bad = () -> { throw new IllegalStateException("read"); };
        for (long tick = 0; tick < 1200; tick += 10) {
            assertFalse(gate.run(tick, bad, e -> errors.incrementAndGet()));
        }
        assertEquals(1, errors.get());
        assertFalse(gate.run(1200, bad, e -> errors.incrementAndGet()));
        assertEquals(2, errors.get());
    }

    @Test
    void reportFailureDoesNotCrashTheServerEither() {
        var gate = new ScoreboardReadGuard();
        assertDoesNotThrow(() -> gate.run(0,
                () -> { throw new IllegalStateException("read"); },
                e -> { throw new IllegalStateException("report"); }));
        assertTrue(gate.unavailable());
        assertEquals(1, gate.lastFailure().getSuppressed().length);
    }
}
