package dev.mineagent.runtime.client.webui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
class UiStateStoreTest {
    @TempDir Path dir;
    @Test void restoresUnicodeDraftOnlyWithinTheSameServerWorldViewerScope() throws Exception {
        var store = new UiStateStore(dir);
        store.save("server-a|world-a|viewer-a", "{\"draft\":\"中文草稿\"}");
        assertEquals("{\"draft\":\"中文草稿\"}", new UiStateStore(dir).load("server-a|world-a|viewer-a"));
        assertEquals("{}", store.load("server-b|world-a|viewer-a"));
        assertEquals("{}", store.load("server-a|world-b|viewer-a"));
        assertEquals("{}", store.load("server-a|world-a|viewer-b"));
    }
    @Test void neverUsesScopeAsAPathAndRejectsOversizeStateWithoutOverwritingDraft() throws Exception {
        var store = new UiStateStore(dir); store.save("../../outside", "old");
        assertThrows(IllegalArgumentException.class, () -> store.save("../../outside", "x".repeat(65537)));
        assertEquals("old", store.load("../../outside"));
        try (var files = Files.list(dir)) { assertTrue(files.allMatch(p -> p.getFileName().toString().matches("(?:scope-[a-f0-9-]{36}\\.json|namespace-map-v2\\.(?:json|lock))"))); }
    }
}
