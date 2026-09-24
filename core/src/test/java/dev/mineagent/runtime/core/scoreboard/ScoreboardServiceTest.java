package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreObjectiveSnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreOperation;
import dev.mineagent.runtime.api.scoreboard.ScoreboardCommand;
import dev.mineagent.runtime.api.scoreboard.ScoreboardMutationResult;
import dev.mineagent.runtime.api.scoreboard.ScoreboardPort;
import dev.mineagent.runtime.api.scoreboard.ScoreboardSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardServiceTest {
    @TempDir
    Path temporaryDirectory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);
    @Test void worldBoardBatchDoesNotScanScoresForInvisibleViewsAndSharesOneSnapshotAcrossViewers()throws Exception{
        var port=new TestScoreboardPort();port.createObjective("kills","dummy","成绩","INTEGER",true,NumberFormatSpec.defaultFormat());
        var a=new ScoreboardService.BoardViewer(new ScoreAudienceContext(UUID.randomUUID(),java.util.Set.of(),java.util.Set.of(),java.util.Set.of()),"minecraft:overworld",0,65,0);
        var b=new ScoreboardService.BoardViewer(new ScoreAudienceContext(UUID.randomUUID(),java.util.Set.of(),java.util.Set.of(),java.util.Set.of()),"minecraft:overworld",0,65,0);
        try(var service=ScoreboardService.open(temporaryDirectory.resolve("batch.db"),UUID.randomUUID(),clock,port)){
            var source=service.refreshSources().getFirst();int before=port.reads;service.worldBoardBatch(List.of(a,b));assertEquals(before,port.reads);
            service.createView(source.sourceId(),ScoreViewKind.WORLD_BOARD,UUID.randomUUID(),ScoreAudience.publicAudience(),Map.of(),ScoreViewTarget.world("minecraft:overworld",1,65,0,0,1));
            before=port.reads;var batch=service.worldBoardBatch(List.of(a,b));assertEquals(before+1,port.reads);assertEquals(1,batch.get(a.audience().playerId()).size());assertEquals(1,batch.get(b.audience().playerId()).size());
        }
    }

    @Test void worldProjectionPlacementIsPresentationOnlyCASAndPersistsWithoutDeletingItsScoreSource() throws Exception {
        var port=new TestScoreboardPort();port.createObjective("kills","dummy","成绩","INTEGER",true,NumberFormatSpec.defaultFormat());port.setScore("kills","Alice",7);
        UUID world=UUID.randomUUID(),pkg=UUID.randomUUID(),id;
        var target=ScoreViewTarget.world("minecraft:overworld",4,67,2,135,1.5f);
        try(var service=ScoreboardService.open(temporaryDirectory.resolve("placement.db"),world,clock,port)){
            var source=service.refreshSources().getFirst();var view=service.createView(source.sourceId(),ScoreViewKind.HUD,pkg,ScoreAudience.publicAudience(),Map.of("title","观察"),ScoreViewTarget.hud("TOP_LEFT"));id=view.viewId();
            org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,()->service.placeWorldView(id,UUID.randomUUID(),1,target));
            var moved=service.placeWorldView(id,pkg,1,target);assertEquals(ScoreViewKind.WORLD_BOARD,moved.kind());assertEquals(2,moved.revision());assertEquals(target,moved.target());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->service.patchLayout(id,pkg,2,Map.of("viewDistance","bad")));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.placeWorldView(id,pkg,1,target));assertEquals(7,port.score("kills","Alice"));
        }
        try(var service=ScoreboardService.open(temporaryDirectory.resolve("placement.db"),world,clock,port)){
            var restored=service.view(id).orElseThrow();assertEquals(target,restored.target());assertEquals(2,restored.revision());
            var detached=service.detachWorldView(id,pkg,2);assertEquals(ScoreViewKind.HUD,detached.kind());assertEquals("观察",service.project(id).title());assertEquals(7,port.score("kills","Alice"));
            service.deleteView(id);assertEquals(1,port.snapshot().objectives().size());assertEquals(7,port.score("kills","Alice"));
        }
    }

    @Test
    void editsTheExistingVanillaObjectiveWithoutCreatingAShadowAndReplaysDuplicateRequest() throws Exception {
        var port = new TestScoreboardPort();
        port.createObjective("kills", "dummy", "击杀", "INTEGER", true, NumberFormatSpec.defaultFormat());
        port.setScore("kills", "Alice", 2);
        UUID worldId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ScoreboardCommand command;
        try (var service = ScoreboardService.open(
                temporaryDirectory.resolve("score.db"), worldId, clock, port)) {
            ScoreSourceBinding source = service.refreshSources().stream()
                    .filter(value -> value.reference().equals("kills")).findFirst().orElseThrow();
            assertEquals(ScoreSourceOwnership.EXTERNAL, source.ownership());
            assertEquals(ScoreAccessMode.READ_ONLY, source.accessMode());
            var forbidden = service.execute(new ScoreboardCommand(UUID.randomUUID(), service.revision(),
                    "score.add", Map.of("sourceId", source.sourceId().toString(), "holder", "Alice", "value", "3")),
                    true);
            assertEquals("SOURCE_READ_ONLY", forbidden.errorCode());

            source = service.grantExternalWrite(source.sourceId(), source.revision(), true);
            long expectedRevision = service.revision();
            command = new ScoreboardCommand(requestId, expectedRevision, "score.add",
                    Map.of("sourceId", source.sourceId().toString(), "holder", "Alice", "value", "3"));
            var first = service.execute(command, true);
            var replay = service.execute(command, true);
            var stale = service.execute(new ScoreboardCommand(UUID.randomUUID(), expectedRevision, "score.add",
                    command.arguments()), true);

            assertTrue(first.accepted());
            assertEquals(first, replay);
            assertEquals("STALE_REVISION", stale.errorCode());
            assertEquals(5, port.score("kills", "Alice"));
            assertEquals(1, port.snapshot().objectives().size());
        }
        try (var reopened = ScoreboardService.open(
                temporaryDirectory.resolve("score.db"), worldId, clock, port)) {
            var replayAfterRestart = reopened.execute(command, true);
            assertTrue(replayAfterRestart.accepted());
            assertEquals(5, port.score("kills", "Alice"));
        }
    }

    @Test
    void multipleViewsShareOneSourceAndSlotReleaseNeverOverwritesExternalChanges() throws Exception {
        var port = new TestScoreboardPort();
        port.createObjective("kills", "dummy", "击杀", "INTEGER", true, NumberFormatSpec.defaultFormat());
        port.createObjective("other", "dummy", "其他", "INTEGER", true, NumberFormatSpec.defaultFormat());
        port.setDisplaySlot("sidebar", "other");
        UUID ownerPackage = UUID.randomUUID();
        try (var service = ScoreboardService.open(
                temporaryDirectory.resolve("views.db"), UUID.randomUUID(), clock, port)) {
            ScoreSourceBinding source = service.refreshSources().stream()
                    .filter(value -> value.reference().equals("kills")).findFirst().orElseThrow();
            ScoreView hud = service.createView(source.sourceId(), ScoreViewKind.HUD, ownerPackage,
                    ScoreAudience.publicAudience(), Map.of("topN", "10"), ScoreViewTarget.hud("TOP_LEFT"));
            ScoreView board = service.createView(source.sourceId(), ScoreViewKind.WORLD_BOARD, ownerPackage,
                    ScoreAudience.publicAudience(), Map.of("scale", "1.0"),
                    ScoreViewTarget.world("minecraft:overworld", 1, 65, 2, 0, 1));

            assertEquals(hud.sourceId(), board.sourceId());
            assertEquals("SLOT_OCCUPIED", service.claimDisplaySlot(hud.viewId(), "sidebar", false).errorCode());
            assertTrue(service.claimDisplaySlot(hud.viewId(), "sidebar", true).accepted());
            assertEquals("kills", port.snapshot().displaySlots().get("sidebar"));
            port.setDisplaySlot("sidebar", "other");
            service.releaseDisplaySlot(hud.viewId());
            assertEquals("other", port.snapshot().displaySlots().get("sidebar"));

            service.deleteView(hud.viewId());
            assertTrue(service.source(source.sourceId()).isPresent());
            service.unloadPackage(ownerPackage);
            assertTrue(service.views().isEmpty());
            assertTrue(port.objective("kills"));
        }
    }

    @Test void observesRuntimeAndExternalLongNamesAndReopensWithoutRenamingOrWritingScores()throws Exception{
        var port=new TestScoreboardPort();String runtime="ma_"+"0123456789abcdef".repeat(2),external="external_"+"x".repeat(300),holder="player_"+"h".repeat(80);UUID world=UUID.randomUUID(),pkg=UUID.randomUUID();
        port.createObjective(runtime,"dummy","已上锁门","INTEGER",true,NumberFormatSpec.defaultFormat());port.createObjective(external,"dummy","External","INTEGER",true,NumberFormatSpec.defaultFormat());port.setScore(runtime,holder,17);
        var file=temporaryDirectory.resolve("modern-native-names.db");UUID sourceId;
        try(var service=ScoreboardService.open(file,world,clock,port)){
            var sources=service.refreshSources();assertEquals(2,sources.size());var source=sources.stream().filter(v->v.reference().equals(runtime)).findFirst().orElseThrow();sourceId=source.sourceId();assertEquals(ScoreAccessMode.READ_ONLY,source.accessMode());
            var view=service.createView(sourceId,ScoreViewKind.HUD,pkg,ScoreAudience.publicAudience(),Map.of(),ScoreViewTarget.hud("TOP_LEFT"));var data=service.project(view.viewId());assertEquals(holder,data.rows().getFirst().holder());assertEquals(17,data.rows().getFirst().score());assertEquals(2,port.snapshot().objectives().size());
        }
        try(var service=ScoreboardService.open(file,world,clock,port)){assertEquals(2,service.refreshSources().size());assertEquals(runtime,service.source(sourceId).orElseThrow().reference());assertTrue(service.sources().stream().anyMatch(v->v.reference().equals(external)));assertEquals(17,port.score(runtime,holder));}
    }

    private static final class TestScoreboardPort implements ScoreboardPort {
        private int reads;
        private final Map<String, ScoreObjectiveSnapshot> objectives = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> scores = new LinkedHashMap<>();
        private final Map<String, String> slots = new LinkedHashMap<>();

        int score(String objective, String holder) {
            return scores.getOrDefault(objective, Map.of()).getOrDefault(holder, 0);
        }

        boolean objective(String name) {
            return objectives.containsKey(name);
        }

        @Override
        public ScoreboardSnapshot snapshot() {
            reads++;
            var entries = new ArrayList<ScoreEntrySnapshot>();
            scores.forEach((objective, holders) -> holders.forEach((holder, score) -> entries.add(
                    new ScoreEntrySnapshot(objective, holder, score, holder, NumberFormatSpec.defaultFormat()))));
            return new ScoreboardSnapshot(List.copyOf(objectives.values()), entries, slots);
        }

        @Override
        public ScoreboardMutationResult createObjective(String name, String criteria, String displayName,
                String renderType, boolean autoUpdate, NumberFormatSpec numberFormat) {
            objectives.put(name, new ScoreObjectiveSnapshot(name, criteria, false, displayName,
                    renderType, autoUpdate, numberFormat));
            scores.putIfAbsent(name, new LinkedHashMap<>());
            return accepted();
        }

        @Override
        public ScoreboardMutationResult removeObjective(String name) {
            objectives.remove(name);
            scores.remove(name);
            slots.values().removeIf(name::equals);
            return accepted();
        }

        @Override
        public ScoreboardMutationResult updateObjective(String name, String displayName, String renderType,
                boolean autoUpdate, NumberFormatSpec numberFormat) {
            var old = objectives.get(name);
            objectives.put(name, new ScoreObjectiveSnapshot(name, old.criteria(), old.readOnly(), displayName,
                    renderType, autoUpdate, numberFormat));
            return accepted();
        }

        @Override
        public ScoreboardMutationResult setScore(String objectiveName, String holder, int value) {
            scores.get(objectiveName).put(holder, value);
            return accepted();
        }

        @Override
        public ScoreboardMutationResult addScore(String objectiveName, String holder, int delta) {
            scores.get(objectiveName).merge(holder, delta, Math::addExact);
            return accepted();
        }

        @Override
        public ScoreboardMutationResult resetScore(String objectiveName, String holder) {
            scores.get(objectiveName).remove(holder);
            return accepted();
        }

        @Override
        public ScoreboardMutationResult resetHolder(String holder) {
            scores.values().forEach(values -> values.remove(holder));
            return accepted();
        }

        @Override
        public ScoreboardMutationResult setScoreDisplay(String objectiveName, String holder,
                String displayName, NumberFormatSpec numberFormat) {
            return accepted();
        }

        @Override
        public ScoreboardMutationResult operate(String targetObjective, String targetHolder, ScoreOperation operation,
                String sourceObjective, String sourceHolder) {
            return accepted();
        }

        @Override
        public ScoreboardMutationResult setDisplaySlot(String slot, String objectiveName) {
            if (objectiveName == null || objectiveName.isBlank()) {
                slots.remove(slot);
            } else {
                slots.put(slot, objectiveName);
            }
            return accepted();
        }

        private ScoreboardMutationResult accepted() {
            return ScoreboardMutationResult.accepted(snapshot());
        }
    }
}
