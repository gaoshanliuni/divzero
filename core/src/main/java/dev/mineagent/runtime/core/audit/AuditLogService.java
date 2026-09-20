package dev.mineagent.runtime.core.audit;

import dev.mineagent.runtime.core.persistence.AuditRecord;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class AuditLogService implements AutoCloseable {
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(30);
    private static final long DEFAULT_MAXIMUM_BYTES = 5L * 1024 * 1024 * 1024;
    private static final int MAX_PAYLOAD_CHARACTERS = 1_000_000;
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final long maximumBytes;
    private final Duration retention;

    private AuditLogService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            long maximumBytes,
            Duration retention
    ) throws Exception {
        if (repository == null || worldId == null || clock == null || maximumBytes < 1
                || retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("invalid audit log configuration");
        }
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.maximumBytes = maximumBytes;
        this.retention = retention;
        prune();
    }

    public static AuditLogService open(Path database, UUID worldId, Clock clock) throws Exception {
        return open(database, worldId, clock, DEFAULT_MAXIMUM_BYTES, DEFAULT_RETENTION);
    }

    public static AuditLogService open(
            Path database,
            UUID worldId,
            Clock clock,
            long maximumBytes,
            Duration retention
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new AuditLogService(repository, worldId, clock, maximumBytes, retention);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized void record(
            String actor,
            String action,
            String target,
            String payload
    ) throws Exception {
        String bounded = payload == null ? "" : payload;
        if (bounded.length() > MAX_PAYLOAD_CHARACTERS) {
            bounded = bounded.substring(0, MAX_PAYLOAD_CHARACTERS) + "…[TRUNCATED]";
        }
        repository.appendAudit(worldId, actor, action, target, SecretRedactor.redact(bounded), clock.millis());
        prune();
    }

    public synchronized List<AuditRecord> recent(int limit) throws Exception {
        return repository.audit(worldId, limit);
    }

    private void prune() throws Exception {
        repository.pruneAudit(worldId, Math.max(0, clock.millis() - retention.toMillis()), maximumBytes);
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
