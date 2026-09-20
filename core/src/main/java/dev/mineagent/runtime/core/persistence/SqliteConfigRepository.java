package dev.mineagent.runtime.core.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;

public final class SqliteConfigRepository implements AutoCloseable {
    private final Connection connection;

    public SqliteConfigRepository(Path database) throws Exception {
        Path absolute = database.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        initialize();
    }

    private void initialize() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_permission_generations_v1(id TEXT PRIMARY KEY,generation INTEGER NOT NULL)");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mineagent_metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT OR IGNORE INTO mineagent_metadata(key, value)
                    VALUES ('config_revision', '0')
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mineagent_config (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        secret INTEGER NOT NULL CHECK(secret IN (0, 1))
                    )
                    """);
        }
    }

    public synchronized StoredConfig load() throws SQLException {
        long revision;
        try (var statement = connection.prepareStatement(
                "SELECT value FROM mineagent_metadata WHERE key = 'config_revision'");
             var result = statement.executeQuery()) {
            revision = result.next() ? Long.parseLong(result.getString(1)) : 0;
        }

        var publicValues = new LinkedHashMap<String, String>();
        var secretValues = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement(
                "SELECT key, value, secret FROM mineagent_config ORDER BY key");
             var result = statement.executeQuery()) {
            while (result.next()) {
                (result.getBoolean(3) ? secretValues : publicValues)
                        .put(result.getString(1), result.getString(2));
            }
        }
        return new StoredConfig(revision, publicValues, secretValues);
    }

    public synchronized boolean save(long expectedRevision, StoredConfig next) throws SQLException {
        if (next.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("next revision must increment expected revision by one");
        }
        connection.setAutoCommit(false);
        try {
            int updated;
            try (var statement = connection.prepareStatement("""
                    UPDATE mineagent_metadata
                    SET value = ?
                    WHERE key = 'config_revision' AND value = ?
                    """)) {
                statement.setString(1, Long.toString(next.revision()));
                statement.setString(2, Long.toString(expectedRevision));
                updated = statement.executeUpdate();
            }
            if (updated != 1) {
                connection.rollback();
                return false;
            }
            var previous=new LinkedHashMap<String,String>();try(var query=connection.prepareStatement("SELECT key,value FROM mineagent_config WHERE secret=0 AND key LIKE 'permission.player.%'");var rows=query.executeQuery()){while(rows.next())previous.put(rows.getString(1),rows.getString(2));}
            var generations=dev.mineagent.runtime.core.config.PermissionGenerations.advance(permissionGenerations(),previous,next.publicValues());
            try(var query=connection.prepareStatement("INSERT INTO mineagent_permission_generations_v1(id,generation) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET generation=excluded.generation")){for(var e:generations.entrySet()){query.setString(1,e.getKey());query.setLong(2,e.getValue());query.addBatch();}query.executeBatch();}

            try (var clear = connection.prepareStatement("DELETE FROM mineagent_config")) {
                clear.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO mineagent_config(key, value, secret) VALUES (?, ?, ?)")) {
                for (var entry : next.publicValues().entrySet()) {
                    insert.setString(1, entry.getKey());
                    insert.setString(2, entry.getValue());
                    insert.setBoolean(3, false);
                    insert.addBatch();
                }
                for (var entry : next.secretValues().entrySet()) {
                    insert.setString(1, entry.getKey());
                    insert.setString(2, entry.getValue());
                    insert.setBoolean(3, true);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            return true;
        } catch (SQLException|RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }
    public synchronized java.util.Map<String,Long> permissionGenerations()throws SQLException{var result=new LinkedHashMap<String,Long>();try(var query=connection.prepareStatement("SELECT id,generation FROM mineagent_permission_generations_v1");var rows=query.executeQuery()){while(rows.next()){long value=rows.getLong(2);if(value<0)throw new SQLException("PERMISSION_GENERATION_INVALID");result.put(rows.getString(1),value);}}return java.util.Map.copyOf(result);}

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
