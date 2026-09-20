package dev.mineagent.runtime.api.recovery;

import java.util.UUID;

public record SnapshotPreview(UUID snapshotId, String label, int blockCount, long estimatedBytes) {
}
