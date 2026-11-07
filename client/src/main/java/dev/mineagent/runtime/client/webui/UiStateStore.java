package dev.mineagent.runtime.client.webui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Local drafts/layout only. This file never grants server permissions or auto-submits a restored answer. */
public final class UiStateStore {
    private final Path root;
    public UiStateStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    private Path file(String scope) throws IOException {
        if (scope == null || scope.isBlank() || scope.length() > 4096) throw new IllegalArgumentException("UI_STATE_SCOPE");
        String id=dev.mineagent.runtime.core.content.LegacyNamespaceIds.resolve(root,dev.mineagent.runtime.core.content.LegacyNamespaceIds.Kind.UI_STATE_SCOPE,scope,dev.mineagent.runtime.core.content.LegacyNamespaceIds.legacyFiles(root,false),old->Files.isRegularFile(root.resolve(old+".json"),LinkOption.NOFOLLOW_LINKS));return root.resolve(id+".json");
    }
    public synchronized String load(String scope) throws IOException {
        Path file = file(scope);
        if (!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return "{}";
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("UI_STATE_FILE_LINK");
        if (Files.size(file) > 262144) throw new IOException("UI_STATE_TOO_LARGE");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
    public synchronized void save(String scope, String state) throws IOException {
        if (state == null || state.length() > 65536) throw new IllegalArgumentException("UI_STATE_BUDGET");
        Files.createDirectories(root);
        Path target = file(scope), temp = Files.createTempFile(root, "state-", ".tmp");
        try {
            Files.writeString(temp, state, StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
