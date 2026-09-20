package dev.mineagent.runtime.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.memory.MemoryEntry;
import dev.mineagent.runtime.api.memory.MemoryKind;
import dev.mineagent.runtime.api.memory.MemoryMutationResult;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MemoryService implements AutoCloseable {
    private static final String NAMESPACE = "memory";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, MemoryEntry> entries = new LinkedHashMap<>();

    private MemoryService(SqliteRuntimeRepository repository, UUID worldId, Clock clock) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        for (var record : repository.list(worldId, NAMESPACE)) {
            MemoryEntry entry = mapper.readValue(record.payload(), MemoryEntry.class);
            entries.put(entry.memoryId(), entry);
        }
    }

    public static MemoryService open(Path database, UUID worldId, Clock clock) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new MemoryService(repository, worldId, clock);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized MemoryEntry create(UUID owner, MemoryKind kind, String key, String value) throws Exception {
        validate(owner, kind, key, value);
        UUID id = UUID.randomUUID();
        var entry = new MemoryEntry(id, worldId, owner, kind, key.strip(), value.strip(), 1, clock.millis());
        var result = repository.compareAndSet(worldId, NAMESPACE, id.toString(), 0,
                mapper.writeValueAsString(entry), entry.updatedAtEpochMillis());
        if (!result.accepted()) {
            throw new IllegalStateException("memory id collision");
        }
        entries.put(id, entry);
        return entry;
    }

    public synchronized Optional<MemoryEntry> get(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    public synchronized List<MemoryEntry> visibleTo(UUID playerId, boolean operator) {
        return entries.values().stream()
                .filter(entry -> operator || entry.kind() == MemoryKind.WORLD_FACT
                        || entry.ownerPlayerId().equals(playerId))
                .sorted(java.util.Comparator.comparingLong(MemoryEntry::updatedAtEpochMillis).reversed())
                .toList();
    }

    public synchronized MemoryMutationResult update(
            UUID id,
            long expectedRevision,
            boolean authorized,
            String value
    ) throws Exception {
        MemoryEntry current = require(id);
        if (!authorized) {
            return MemoryMutationResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return MemoryMutationResult.rejected(current, "STALE_REVISION");
        }
        validate(current.ownerPlayerId(), current.kind(), current.key(), value);
        var next = new MemoryEntry(current.memoryId(), current.worldId(), current.ownerPlayerId(),
                current.kind(), current.key(), value.strip(), current.revision() + 1, clock.millis());
        return save(current, next);
    }

    public synchronized MemoryMutationResult delete(
            UUID id,
            long expectedRevision,
            boolean authorized
    ) throws Exception {
        MemoryEntry current = require(id);
        if (!authorized) {
            return MemoryMutationResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return MemoryMutationResult.rejected(current, "STALE_REVISION");
        }
        var result = repository.delete(worldId, NAMESPACE, id.toString(), expectedRevision, clock.millis());
        if (!result.accepted()) {
            return MemoryMutationResult.rejected(current, "STALE_REVISION");
        }
        entries.remove(id);
        return MemoryMutationResult.accepted(new MemoryEntry(
                current.memoryId(), current.worldId(), current.ownerPlayerId(), current.kind(), current.key(),
                current.value(), current.revision() + 1, clock.millis()));
    }

    private MemoryMutationResult save(MemoryEntry current, MemoryEntry next) throws Exception {
        var result = repository.compareAndSet(worldId, NAMESPACE, current.memoryId().toString(),
                current.revision(), mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!result.accepted()) {
            MemoryEntry latest = mapper.readValue(result.record().payload(), MemoryEntry.class);
            entries.put(latest.memoryId(), latest);
            return MemoryMutationResult.rejected(latest, "STALE_REVISION");
        }
        entries.put(next.memoryId(), next);
        return MemoryMutationResult.accepted(next);
    }

    private MemoryEntry require(UUID id) {
        MemoryEntry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("unknown memory entry");
        }
        return entry;
    }

    private static void validate(UUID owner, MemoryKind kind, String key, String value) {
        if (owner == null || kind == null || key == null || key.isBlank()
                || key.codePointCount(0, key.length()) > 128 || value == null || value.isBlank()
                || value.codePointCount(0, value.length()) > 16_384) {
            throw new IllegalArgumentException("invalid memory entry");
        }
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
