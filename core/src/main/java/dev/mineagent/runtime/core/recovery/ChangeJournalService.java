package dev.mineagent.runtime.core.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.recovery.BlockChange;
import dev.mineagent.runtime.api.recovery.BlockSnapshot;
import dev.mineagent.runtime.api.recovery.ChangeJournalEntry;
import dev.mineagent.runtime.api.recovery.ChangeJournalResult;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ChangeJournalService implements AutoCloseable {
    private static final String NAMESPACE = "change_journal";
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_BLOCKS_PER_ENTRY = 100_000;

    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final Duration retention;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, ChangeJournalEntry> entries = new LinkedHashMap<>();

    private ChangeJournalService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            Duration retention
    ) throws Exception {
        if (worldId == null || clock == null || retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("invalid change journal configuration");
        }
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.retention = retention;
        long cutoff = clock.millis() - retention.toMillis();
        for (var stored : repository.list(worldId, NAMESPACE)) {
            var entry = mapper.readValue(stored.payload(), ChangeJournalEntry.class);
            if (entry.createdAtEpochMillis() < cutoff) {
                repository.delete(worldId, NAMESPACE, stored.recordId(), stored.revision(), clock.millis());
            } else {
                entries.put(entry.changeId(), entry);
            }
        }
        trim();
    }

    public static ChangeJournalService open(Path database, UUID worldId, Clock clock) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new ChangeJournalService(repository, worldId, clock, Duration.ofDays(30));
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized ChangeJournalEntry record(UUID actorId, String action, List<BlockChange> changes)
            throws Exception {
        if (actorId == null || action == null || action.isBlank() || action.length() > 128
                || changes == null || changes.isEmpty() || changes.size() > MAX_BLOCKS_PER_ENTRY) {
            throw new IllegalArgumentException("invalid change journal batch");
        }
        UUID id = UUID.randomUUID();
        var entry = new ChangeJournalEntry(id, worldId, actorId, action.strip(), changes,
                1, false, clock.millis());
        var saved = repository.compareAndSet(worldId, NAMESPACE, id.toString(), 0,
                mapper.writeValueAsString(entry), entry.createdAtEpochMillis());
        if (!saved.accepted()) {
            throw new IllegalStateException("change journal id collision");
        }
        entries.put(id, entry);
        trim();
        return entry;
    }

    public synchronized Optional<ChangeJournalEntry> get(UUID changeId) {
        return Optional.ofNullable(entries.get(changeId));
    }

    public synchronized List<ChangeJournalEntry> all() {
        return entries.values().stream()
                .sorted(java.util.Comparator.comparingLong(ChangeJournalEntry::createdAtEpochMillis).reversed())
                .toList();
    }

    public synchronized List<BlockSnapshot> revertPlan(UUID changeId) {
        return require(changeId).changes().stream().map(BlockChange::before).toList();
    }

    public synchronized ChangeJournalResult markReverted(UUID changeId, long expectedRevision, boolean authorized)
            throws Exception {
        ChangeJournalEntry current = require(changeId);
        if (!authorized) {
            return ChangeJournalResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return ChangeJournalResult.rejected(current, "STALE_REVISION");
        }
        if (current.reverted()) {
            return ChangeJournalResult.rejected(current, "ALREADY_REVERTED");
        }
        var next = new ChangeJournalEntry(current.changeId(), current.worldId(), current.actorId(), current.action(),
                current.changes(), current.revision() + 1, true, current.createdAtEpochMillis());
        var saved = repository.compareAndSet(worldId, NAMESPACE, changeId.toString(), current.revision(),
                mapper.writeValueAsString(next), clock.millis());
        if (!saved.accepted()) {
            return ChangeJournalResult.rejected(current, "STALE_REVISION");
        }
        entries.put(changeId, next);
        return ChangeJournalResult.accepted(next);
    }

    private ChangeJournalEntry require(UUID id) {
        ChangeJournalEntry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("unknown change journal entry");
        }
        return entry;
    }

    private void trim() throws Exception {
        while (entries.size() > MAX_ENTRIES) {
            ChangeJournalEntry oldest = entries.values().stream()
                    .min(java.util.Comparator.comparingLong(ChangeJournalEntry::createdAtEpochMillis)
                            .thenComparing(ChangeJournalEntry::changeId)).orElseThrow();
            var deleted = repository.delete(worldId, NAMESPACE, oldest.changeId().toString(),
                    oldest.revision(), clock.millis());
            if (!deleted.accepted()) {
                throw new IllegalStateException("failed to trim change journal");
            }
            entries.remove(oldest.changeId());
        }
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
