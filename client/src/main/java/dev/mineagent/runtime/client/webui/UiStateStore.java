package dev.mineagent.runtime.client.webui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Local drafts/layout only. This file never grants server permissions or auto-submits a restored answer. */
public final class UiStateStore {
    private final Path root;
    public UiStateStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    private Path file(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 4096) throw new IllegalArgumentException("UI_STATE_SCOPE");
        try { return root.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(StandardCharsets.UTF_8))) + ".json"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized String load(String scope) throws IOException {
        Path file = file(scope);
        if (!Files.exists(file)) return "{}";
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
