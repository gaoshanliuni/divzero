package dev.mineagent.runtime.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class SnapshotSignature {
    public static final String SIGNATURE_KEY = "security.snapshotSignature";

    private SnapshotSignature() {
    }

    public static byte[] canonicalBytes(long revision, Map<String, String> values) {
        var canonical = new StringBuilder().append(revision).append('\n');
        values.entrySet().stream()
                .filter(entry -> !SIGNATURE_KEY.equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey().length()).append(':').append(entry.getKey())
                        .append('=').append(entry.getValue().length()).append(':').append(entry.getValue()).append('\n'));
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }
}
