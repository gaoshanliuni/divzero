package dev.mineagent.runtime.core.recovery;

import dev.mineagent.runtime.api.recovery.BlockChange;
import dev.mineagent.runtime.api.recovery.BlockSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeJournalServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsVersionedChangeBatchAndSupportsIdempotentUndoMark() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID changeId;
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);
        try (var service = ChangeJournalService.open(database, world, clock)) {
            var entry = service.record(actor, "BUILD", List.of(new BlockChange(
                    new BlockSnapshot("minecraft:overworld", 1, 64, 2, "minecraft:stone", "{x:1}"),
                    new BlockSnapshot("minecraft:overworld", 1, 64, 2, "minecraft:gold_block", ""))));
            changeId = entry.changeId();
            var stale = service.markReverted(changeId, entry.revision() + 1, true);
            var reverted = service.markReverted(changeId, entry.revision(), true);

            assertFalse(stale.accepted());
            assertEquals("STALE_REVISION", stale.errorCode());
            assertTrue(reverted.accepted());
            assertTrue(reverted.entry().reverted());
            assertEquals("minecraft:stone", service.revertPlan(changeId).getFirst().state());
        }
        try (var reopened = ChangeJournalService.open(database, world, clock)) {
            assertTrue(reopened.get(changeId).orElseThrow().reverted());
        }
    }
}
