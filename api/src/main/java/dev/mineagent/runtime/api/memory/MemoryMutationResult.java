package dev.mineagent.runtime.api.memory;

import java.util.Objects;

public record MemoryMutationResult(boolean accepted, String errorCode, MemoryEntry entry) {
    public MemoryMutationResult {
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(entry, "entry");
    }

    public static MemoryMutationResult accepted(MemoryEntry entry) {
        return new MemoryMutationResult(true, "", entry);
    }

    public static MemoryMutationResult rejected(MemoryEntry entry, String code) {
        return new MemoryMutationResult(false, code, entry);
    }
}
