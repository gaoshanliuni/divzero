package dev.mineagent.runtime.client.audio;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class VoiceChunkAssembler {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_AGE_MILLIS = 60_000;
    private final int maxTransferBytes;
    private final int maxChunks;
    private final Clock clock;
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();

    public VoiceChunkAssembler(int maxTransferBytes, int maxChunks, Clock clock) {
        if (maxTransferBytes < 1 || maxChunks < 1 || clock == null) {
            throw new IllegalArgumentException("invalid voice assembler limits");
        }
        this.maxTransferBytes = maxTransferBytes;
        this.maxChunks = maxChunks;
        this.clock = clock;
    }

    public synchronized Optional<byte[]> accept(
            String sha256,
            int chunkIndex,
            int chunkCount,
            byte[] bytes
    ) {
        pruneExpired();
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("invalid audio SHA-256");
        }
        if (chunkCount < 1 || chunkCount > maxChunks || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("invalid audio chunk coordinates");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > maxTransferBytes) {
            throw new IllegalArgumentException("invalid audio chunk size");
        }
        Transfer transfer = transfers.computeIfAbsent(
                sha256, ignored -> new Transfer(chunkCount, clock.millis()));
        if (transfer.chunks.length != chunkCount) {
            throw new IllegalArgumentException("audio chunk count changed");
        }
        byte[] previous = transfer.chunks[chunkIndex];
        if (previous != null) {
            if (!Arrays.equals(previous, bytes)) {
                throw new IllegalArgumentException("conflicting audio chunk replay");
            }
            return Optional.empty();
        }
        if (transfer.totalBytes + bytes.length > maxTransferBytes) {
            transfers.remove(sha256);
            throw new IllegalArgumentException("audio transfer exceeds limit");
        }
        transfer.chunks[chunkIndex] = bytes.clone();
        transfer.totalBytes += bytes.length;
        transfer.received++;
        if (transfer.received != chunkCount) {
            return Optional.empty();
        }
        var output = new ByteArrayOutputStream(transfer.totalBytes);
        for (byte[] chunk : transfer.chunks) {
            output.writeBytes(chunk);
        }
        transfers.remove(sha256);
        byte[] completed = output.toByteArray();
        if (!sha256.equals(hash(completed))) {
            throw new IllegalArgumentException("completed audio hash mismatch");
        }
        return Optional.of(completed);
    }

    public synchronized void clear() {
        transfers.clear();
    }

    private void pruneExpired() {
        long cutoff = clock.millis() - MAX_AGE_MILLIS;
        transfers.values().removeIf(transfer -> transfer.createdAt < cutoff);
    }

    private static String hash(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Transfer {
        private final byte[][] chunks;
        private final long createdAt;
        private int received;
        private int totalBytes;

        private Transfer(int chunkCount, long createdAt) {
            this.chunks = new byte[chunkCount][];
            this.createdAt = createdAt;
        }
    }
}
