package dev.mineagent.runtime.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRuntimeRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void compareAndSetPersistsNamespacedRecordsAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        UUID world = UUID.randomUUID();
        try (var repository = new SqliteRuntimeRepository(database)) {
            var created = repository.compareAndSet(
                    world, "tasks", "task-1", 0, "{\"title\":\"建造\"}", 100);
            var updated = repository.compareAndSet(
                    world, "tasks", "task-1", 1, "{\"title\":\"建造球场\"}", 200);

            assertTrue(created.accepted());
            assertEquals(1, created.record().revision());
            assertTrue(updated.accepted());
            assertEquals(2, updated.record().revision());
        }

        try (var reopened = new SqliteRuntimeRepository(database)) {
            var record = reopened.get(world, "tasks", "task-1").orElseThrow();
            assertEquals(2, record.revision());
            assertEquals("{\"title\":\"建造球场\"}", record.payload());
            assertEquals(1, reopened.list(world, "tasks").size());
            assertEquals(SqliteRuntimeRepository.SCHEMA_VERSION, reopened.schemaVersion());
        }
    }

    @Test
    void staleWriterCannotOverwriteOrDeleteCurrentRecord() throws Exception {
        try (var repository = new SqliteRuntimeRepository(temporaryDirectory.resolve("runtime.db"))) {
            UUID world = UUID.randomUUID();
            repository.compareAndSet(world, "memory", "fact-1", 0, "first", 1);

            var stale = repository.compareAndSet(world, "memory", "fact-1", 0, "stale", 2);
            var staleDelete = repository.delete(world, "memory", "fact-1", 0, 3);

            assertFalse(stale.accepted());
            assertEquals(1, stale.record().revision());
            assertFalse(staleDelete.accepted());
            assertEquals("first", repository.get(world, "memory", "fact-1").orElseThrow().payload());
        }
    }

    @Test
    void deletionCreatesRevisionedTombstoneAndAuditLogIsOrdered() throws Exception {
        try (var repository = new SqliteRuntimeRepository(temporaryDirectory.resolve("runtime.db"))) {
            UUID world = UUID.randomUUID();
            repository.compareAndSet(world, "packages", "pack-1", 0, "active", 1);
            var deleted = repository.delete(world, "packages", "pack-1", 1, 2);
            repository.appendAudit(world, "player-a", "PACKAGE_INSTALL", "pack-1", "{}", 10);
            repository.appendAudit(world, "player-a", "PACKAGE_DELETE", "pack-1", "{}", 20);

            assertTrue(deleted.accepted());
            assertEquals(2, deleted.record().revision());
            assertTrue(deleted.record().deleted());
            assertTrue(repository.get(world, "packages", "pack-1").isEmpty());
            assertEquals("PACKAGE_INSTALL", repository.audit(world, 10).getFirst().action());
            assertEquals("PACKAGE_DELETE", repository.audit(world, 10).getLast().action());
        }
    }

    @Test
    void migratesVersionZeroDatabaseToCurrentSchema() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE mineagent_runtime_schema(version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO mineagent_runtime_schema(version) VALUES (0)");
        }

        try (var repository = new SqliteRuntimeRepository(database)) {
            assertEquals(SqliteRuntimeRepository.SCHEMA_VERSION, repository.schemaVersion());
            assertTrue(repository.list(UUID.randomUUID(), "tasks").isEmpty());
        }
    }
}
