package dev.mineagent.runtime.core.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.recovery.BlockSnapshot;
import dev.mineagent.runtime.api.recovery.LocalSnapshot;
import dev.mineagent.runtime.api.recovery.SnapshotPreview;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SnapshotService implements AutoCloseable {
    private static final String NAMESPACE = "snapshots";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final long maximumBytes;
    private final Duration retention;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, LocalSnapshot> snapshots = new LinkedHashMap<>();

    private SnapshotService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            long maximumBytes,
            Duration retention
    ) throws Exception {
        if (maximumBytes < 1 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("invalid snapshot retention");
        }
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.maximumBytes = maximumBytes;
        this.retention = retention;
        for (var record : repository.list(worldId, NAMESPACE)) {
            LocalSnapshot snapshot = mapper.readValue(record.payload(), LocalSnapshot.class);
            if (snapshot.expiresAtEpochMillis() <= clock.millis()) {
                repository.delete(worldId, NAMESPACE, snapshot.snapshotId().toString(),
                        record.revision(), clock.millis());
            } else {
                snapshots.put(snapshot.snapshotId(), snapshot);
            }
        }
    }

    public static SnapshotService open(
            Path database,
            UUID worldId,
            Clock clock,
            long maximumBytes,
            Duration retention
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new SnapshotService(repository, worldId, clock, maximumBytes, retention);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized LocalSnapshot create(
            UUID ownerPlayerId,
            String label,
            List<BlockSnapshot> blocks
    ) throws Exception {
        if (ownerPlayerId == null || label == null || label.isBlank() || label.length() > 256
                || blocks == null || blocks.isEmpty() || blocks.size() > 1_000_000) {
            throw new IllegalArgumentException("invalid local snapshot");
        }
        long estimated = estimate(blocks);
        long used = snapshots.values().stream().mapToLong(LocalSnapshot::estimatedBytes).sum();
        if (estimated > maximumBytes || used + estimated > maximumBytes) {
            throw new IllegalStateException("snapshot storage limit exceeded");
        }
        UUID id = UUID.randomUUID();
        long now = clock.millis();
        var snapshot = new LocalSnapshot(id, worldId, ownerPlayerId, label.strip(), 1, blocks,
                now, now + retention.toMillis(), estimated);
        var saved = repository.compareAndSet(worldId, NAMESPACE, id.toString(), 0,
                mapper.writeValueAsString(snapshot), now);
        if (!saved.accepted()) {
            throw new IllegalStateException("snapshot id collision");
        }
        snapshots.put(id, snapshot);
        return snapshot;
    }

    public synchronized Optional<LocalSnapshot> get(UUID snapshotId) {
        return Optional.ofNullable(snapshots.get(snapshotId));
    }

    public synchronized List<LocalSnapshot> all() {
        return snapshots.values().stream()
                .sorted(java.util.Comparator.comparingLong(LocalSnapshot::createdAtEpochMillis).reversed())
                .toList();
    }

    public synchronized SnapshotPreview preview(UUID snapshotId) {
        LocalSnapshot snapshot = require(snapshotId);
        return new SnapshotPreview(snapshotId, snapshot.label(), snapshot.blocks().size(), snapshot.estimatedBytes());
    }

    public synchronized List<BlockSnapshot> restorePlan(UUID snapshotId) {
        return require(snapshotId).blocks();
    }

    private LocalSnapshot require(UUID snapshotId) {
        LocalSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("unknown snapshot");
        }
        return snapshot;
    }

    private static long estimate(List<BlockSnapshot> blocks) {
        long bytes = 0;
        for (BlockSnapshot block : blocks) {
            bytes = Math.addExact(bytes,
                    block.dimension().getBytes(StandardCharsets.UTF_8).length
                            + block.state().getBytes(StandardCharsets.UTF_8).length
                            + block.blockEntitySnbt().getBytes(StandardCharsets.UTF_8).length + 32L);
        }
        return bytes;
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
