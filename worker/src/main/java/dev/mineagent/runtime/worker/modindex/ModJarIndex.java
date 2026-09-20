package dev.mineagent.runtime.worker.modindex;

import java.util.List;

public record ModJarIndex(
        String modId,
        String version,
        String displayName,
        String sha256,
        long fileSize,
        List<String> classNames,
        List<String> sourceEntries
) {
    public ModJarIndex {
        classNames = List.copyOf(classNames);
        sourceEntries = List.copyOf(sourceEntries);
    }
}
