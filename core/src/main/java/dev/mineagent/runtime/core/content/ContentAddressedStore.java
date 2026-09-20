package dev.mineagent.runtime.core.content;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentAddressedStore {
    private static final long DEFAULT_MAX_OBJECT_SIZE = 512L * 1024 * 1024;
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private final Path root;
    private final long maxObjectSize;

    public ContentAddressedStore(Path root) {
        this(root, DEFAULT_MAX_OBJECT_SIZE);
    }

    public ContentAddressedStore(Path root, long maxObjectSize) {
        if (root == null || maxObjectSize < 1) {
            throw new IllegalArgumentException("invalid content store configuration");
        }
        this.root = root.toAbsolutePath().normalize();
        this.maxObjectSize = maxObjectSize;
    }

    public StoredObject put(byte[] content) throws IOException {
        if (content == null || content.length > maxObjectSize) {
            throw new IllegalArgumentException("content exceeds object limit");
        }
        String hash = sha256(content);
        Path target = pathFor(hash);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            Path temporary = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
            try {
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    Files.deleteIfExists(temporary);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return new StoredObject(hash, content.length, target);
    }

    public byte[] read(String hash) throws IOException {
        Path path = pathFor(hash);
        byte[] content = Files.readAllBytes(path);
        if (!hash.equals(sha256(content))) {
            throw new ContentIntegrityException("content hash mismatch for " + hash);
        }
        return content;
    }

    public Path pathFor(String hash) {
        if (hash == null || !HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("invalid SHA-256 hash");
        }
        Path resolved = root.resolve(hash.substring(0, 2)).resolve(hash.substring(2)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("content path escapes store");
        }
        return resolved;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
