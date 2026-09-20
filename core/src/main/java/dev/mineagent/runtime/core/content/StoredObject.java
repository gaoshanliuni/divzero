package dev.mineagent.runtime.core.content;

import java.nio.file.Path;

public record StoredObject(String sha256, long size, Path path) {
}
