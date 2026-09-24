package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreObjectiveSnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreboardSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardViewProjectorTest {
    @Test
    void projectsRankedTopNAndTickTimeWithoutChangingAuthoritativeScores() {
        UUID sourceId = UUID.randomUUID();
        var source = new ScoreSourceBinding(sourceId, ScoreSourceBackend.VANILLA, "time",
                ScoreSourceOwnership.EXTERNAL, ScoreAccessMode.READ_ONLY, null, 1, 0);
        var view = new ScoreView(UUID.randomUUID(), sourceId, ScoreViewKind.HUD, UUID.randomUUID(),
                ScoreAudience.publicAudience(), Map.of(
                "title", "用时榜", "sort", "ASC", "topN", "2", "numberFormat", "TICKS_TIME"),
                ScoreViewTarget.hud("TOP_LEFT"), true, 1, 0);
        var snapshot = new ScoreboardSnapshot(List.of(new ScoreObjectiveSnapshot(
                "time", "dummy", false, "原标题", "INTEGER", true, NumberFormatSpec.defaultFormat())),
                List.of(
                        new ScoreEntrySnapshot("time", "Carol", 1_400, "Carol", NumberFormatSpec.defaultFormat()),
                        new ScoreEntrySnapshot("time", "Alice", 1_205, "Alice", NumberFormatSpec.defaultFormat()),
                        new ScoreEntrySnapshot("time", "Bob", 600, "Bob", NumberFormatSpec.defaultFormat())),
                Map.of());

        var projected = new ScoreboardViewProjector().project(view, source, snapshot, 5);

        assertEquals("用时榜", projected.title());
        assertEquals(List.of("Bob", "Alice"), projected.rows().stream().map(row -> row.holder()).toList());
        assertEquals("0:30.00", projected.rows().getFirst().formattedScore());
        assertEquals(600, snapshot.entries().stream().filter(row -> row.holder().equals("Bob"))
                .findFirst().orElseThrow().score());
    }

    @Test
    void audienceResolverFiltersBeforeNetworkProjection() {
        UUID playerId = UUID.randomUUID();
        var resolver = new ScoreboardAudienceResolver();
        var context = new ScoreAudienceContext(playerId, Set.of("runners"), Set.of("trusted"), Set.of("race-1"));

        assertTrue(resolver.visible(ScoreAudience.publicAudience(), context));
        assertTrue(resolver.visible(new ScoreAudience(ScoreAudienceKind.PLAYERS,
                Set.of(playerId.toString())), context));
        assertTrue(resolver.visible(new ScoreAudience(ScoreAudienceKind.TEAM, Set.of("runners")), context));
        assertFalse(resolver.visible(new ScoreAudience(ScoreAudienceKind.SCENE, Set.of("secret")), context));
    }
}
