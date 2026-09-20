package dev.mineagent.runtime.api.packages;

import java.util.Objects;

public record RuntimeEntrypoint(String path, RuntimeResourceSide side, String sha256) {
    public RuntimeEntrypoint {
        requireRelativePath(path);
        Objects.requireNonNull(side, "side");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid entrypoint sha256");
        }
    }

    public static void requireRelativePath(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.startsWith("/")
                || value.startsWith("\\") || value.contains("..") || value.indexOf('\\') >= 0
                || !value.matches("[A-Za-z0-9_.@/-]+")) {
            throw new IllegalArgumentException("invalid relative package path");
        }
    }
}
