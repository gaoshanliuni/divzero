package dev.mineagent.runtime.core.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteRuntimeRepository implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    private final Connection connection;
    private final java.util.concurrent.locks.ReentrantLock writeGate;

    public SqliteRuntimeRepository(Path database) throws Exception {
        Path absolute = database.toAbsolutePath().normalize();
        writeGate = SqliteWriteGate.forFile(absolute);
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        // Reserve the writer before a CAS reads its snapshot. DEFERRED read-to-write
        // upgrades in WAL can fail with SQLITE_BUSY_SNAPSHOT despite busy_timeout
        // when an independent tool journal commits between SELECT and INSERT.
        var properties = new java.util.Properties();
        properties.setProperty("transaction_mode", "IMMEDIATE");
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute, properties);
        initialize();
    }

    private void beginWrite() throws SQLException {
        writeGate.lock();
        try { connection.setAutoCommit(false); }
        catch (SQLException | RuntimeException | Error failure) { writeGate.unlock(); throw failure; }
    }
    private void endWrite() throws SQLException {
        try { connection.setAutoCommit(true); } finally { writeGate.unlock(); }
    }

    private void initialize() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mineagent_runtime_schema (
                        version INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO mineagent_runtime_schema(version)
                    SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM mineagent_runtime_schema)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mineagent_runtime_records (
                        world_id TEXT NOT NULL,
                        namespace TEXT NOT NULL,
                        record_id TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted INTEGER NOT NULL CHECK(deleted IN (0, 1)),
                        PRIMARY KEY(world_id, namespace, record_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS mineagent_runtime_records_namespace
                    ON mineagent_runtime_records(world_id, namespace, updated_at DESC)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mineagent_runtime_audit (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        world_id TEXT NOT NULL,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        target TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
        }
        int currentVersion = schemaVersion();
        if (currentVersion == 0) {
            try (var migrate = connection.prepareStatement(
                    "UPDATE mineagent_runtime_schema SET version = 1 WHERE version = 0")) {
                migrate.executeUpdate();
            }
            currentVersion = 1;
        }
        if (currentVersion != SCHEMA_VERSION) {
            throw new SQLException("unsupported MineAgent schema version " + currentVersion);
        }
    }

    public synchronized int schemaVersion() throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT version FROM mineagent_runtime_schema LIMIT 1");
             var result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
    public synchronized void initializePackageLibrary()throws SQLException{PackageLibraryHistory.initialize(connection);}
    public synchronized PackageLibraryHistory.Usage packageLibraryUsage()throws SQLException{return PackageLibraryHistory.usage(connection);}
    public synchronized PackageLibraryHistory.Catalog packageCatalog(UUID world,UUID owner,String search,int offset,int limit)throws SQLException{return PackageLibraryHistory.catalog(connection,world,owner,search,offset,limit);}
    public synchronized List<PackageLibraryHistory.VersionSummary> packageVersions(UUID id,int offset,int limit)throws SQLException{return PackageLibraryHistory.versions(connection,id,offset,limit);}
    public synchronized PackageLibraryHistory.Version packageVersion(UUID id,long revision)throws SQLException{return PackageLibraryHistory.version(connection,id,revision);}
    public synchronized void packageBaselines(List<RuntimeRecord> records,long observedAt)throws Exception{
        beginWrite();try{for(var record:records)PackageLibraryHistory.record(connection,record,"BASELINE_OBSERVED",observedAt);connection.commit();}catch(Exception failure){connection.rollback();throw failure;}finally{endWrite();}
    }
    /** Head plus exact stored snapshots commit together; a history-capacity failure never replaces the head. */
    public synchronized RuntimeCasResult packageCompareAndSet(UUID id,long expected,String payload,long at)throws Exception{
        UUID world=dev.mineagent.runtime.core.packages.RuntimePackageLibrary.GLOBAL_LIBRARY_ID;String ns=PackageLibraryHistory.NS;
        validateKey(world,ns,id.toString(),expected,payload);beginWrite();
        try{
            var current=select(world,ns,id.toString()).orElse(null);
            if(current==null?expected!=0:current.deleted()||current.revision()!=expected){connection.rollback();return new RuntimeCasResult(false,current==null?missing(world,ns,id.toString()):current);}
            var before=PackageLibraryHistory.usage(connection);long next=expected+1;
            if(current==null){try(var q=connection.prepareStatement("INSERT INTO mineagent_runtime_records(world_id,namespace,record_id,revision,payload,updated_at,deleted) VALUES(?,?,?,1,?,?,0)")){q.setString(1,world.toString());q.setString(2,ns);q.setString(3,id.toString());q.setString(4,payload);q.setLong(5,at);q.executeUpdate();}}
            else{try(var q=connection.prepareStatement("UPDATE mineagent_runtime_records SET revision=?,payload=?,updated_at=? WHERE world_id=? AND namespace=? AND record_id=? AND revision=? AND deleted=0")){q.setLong(1,next);q.setString(2,payload);q.setLong(3,at);q.setString(4,world.toString());q.setString(5,ns);q.setString(6,id.toString());q.setLong(7,expected);if(q.executeUpdate()!=1)throw new IllegalStateException("PACKAGE_LIBRARY_CAS");}}
            var saved=new RuntimeRecord(world,ns,id.toString(),next,payload,at,false);
            PackageLibraryHistory.record(connection,current,"OBSERVED_PREVIOUS_HEAD",at);PackageLibraryHistory.record(connection,saved,"LIBRARY_COMMIT",at);
            PackageLibraryHistory.check(before,PackageLibraryHistory.usage(connection));connection.commit();return new RuntimeCasResult(true,saved);
        }catch(Exception failure){connection.rollback();throw failure;}finally{endWrite();}
    }
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Alias packageAlias(UUID owner,UUID pkg)throws SQLException{return PackageAssetPersistence.alias(connection,owner,pkg);}
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Shelf packageShelf(UUID owner,UUID id)throws Exception{return PackageAssetPersistence.shelf(connection,owner,id);}
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Page<dev.mineagent.runtime.core.packages.PackageAssetMetadata.Shelf> packageShelves(UUID owner,boolean active,int offset)throws Exception{return PackageAssetPersistence.shelves(connection,owner,active,offset,8);}
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Derivation packageDerivation(UUID owner,UUID pkg)throws Exception{return PackageAssetPersistence.derivation(connection,owner,pkg);}
    public synchronized boolean packageCopyOwned(UUID world,UUID owner,UUID pkg,long revision,String hash)throws SQLException{return PackageAssetPersistence.owns(connection,world,owner,pkg,revision,hash);}
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Receipt packageAssetReceipt(UUID world,UUID owner,UUID id)throws Exception{return PackageAssetPersistence.receipt(connection,world,owner,id);}
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Receipt packageAssetMetadata(dev.mineagent.runtime.core.packages.PackageAssetMetadata.Input input,long at)throws Exception{return PackageAssetPersistence.metadata(connection,input,at);}
    public synchronized dev.mineagent.runtime.core.packages.PackageAssetMetadata.Receipt packageAssetCopy(dev.mineagent.runtime.core.packages.PackageAssetMetadata.Input input,String candidate,long at)throws Exception{return PackageAssetPersistence.copy(connection,input,candidate,at);}
    public synchronized dev.mineagent.runtime.core.packages.JavaStudioMetadata.Link studioLink(UUID world,UUID owner,UUID pkg)throws Exception{return JavaStudioPersistence.link(connection,world,owner,pkg);}
    public synchronized dev.mineagent.runtime.core.packages.JavaStudioMetadata.Receipt studioReceipt(UUID world,UUID owner,UUID operation)throws Exception{return JavaStudioPersistence.receipt(connection,world,owner,operation);}
    public synchronized dev.mineagent.runtime.core.packages.JavaStudioMetadata.Receipt studioPublish(dev.mineagent.runtime.core.packages.JavaStudioMetadata.Input input,String payload,long now)throws Exception{return JavaStudioPersistence.publish(connection,input,payload,now);}
    public synchronized void initializePackageJobRetention(UUID world)throws SQLException{PackageJobRetention.initialize(connection,world);}
    public synchronized List<RuntimeRecord> packageJobRows(UUID world,String ns,PackageJobRetention.Query query,int offset,int limit)throws SQLException{return PackageJobRetention.page(connection,world,ns,query,offset,limit);}
    public synchronized long packageJobCount(UUID world,String ns,PackageJobRetention.Query query)throws SQLException{return PackageJobRetention.count(connection,world,ns,query);}
    public synchronized PackageJobRetention.Usage packageJobUsage(UUID world,String ns,UUID owner)throws SQLException{return PackageJobRetention.usage(connection,world,ns,owner);}
    public synchronized boolean packageJobOwnedHead(UUID world,String ns,UUID owner,UUID pkg,long revision,String hash)throws SQLException{return PackageJobRetention.ownedHead(connection,world,ns,owner,pkg,revision,hash);}
    public synchronized void initializeTaskRetention(UUID world)throws SQLException{TaskRetention.initialize(connection,world);TaskBudgetLineage.initialize(connection,world);}
    public synchronized TaskBudgetLineage.Entry taskLineage(UUID world,UUID task)throws SQLException{return TaskBudgetLineage.read(connection,world.toString(),task.toString());}
    public synchronized RuntimeCasResult createTask(UUID world,UUID task,String payload,long at,TaskBudgetLineage.Parent parent)throws SQLException{
        return write(world,"tasks",task.toString(),0,payload,at,false,false,true,parent);
    }
    public synchronized List<RuntimeRecord> taskHistory(UUID world,UUID owner,String state,String archive,int offset,int limit,java.util.Set<UUID> worldTasks)throws SQLException{return TaskRetention.page(connection,world,owner,state,archive,offset,limit,worldTasks);}
    public synchronized long taskCount(UUID world,UUID owner,String state,String archive)throws SQLException{return TaskRetention.count(connection,world,owner,state,archive);}
    public synchronized TaskRetention.Usage taskUsage(UUID world,UUID owner)throws SQLException{return TaskRetention.usage(connection,world,owner);}
    public synchronized void initializeWorldActionRetention(UUID world)throws Exception{WorldActionRetention.initialize(connection,world);}
    public synchronized List<RuntimeRecord> worldActionRows(UUID world,UUID task,Long intent,UUID owner,String kind,int offset,int limit)throws SQLException{return WorldActionRetention.rows(connection,world,task,intent,owner,kind,offset,limit);}
    public synchronized Optional<UUID> worldGenerationOwner(UUID world,UUID operation)throws SQLException{return WorldActionRetention.generation(connection,world,operation);}
    public synchronized WorldActionRetention.Usage worldActionUsage(UUID world,String namespace)throws SQLException{return WorldActionRetention.usage(connection,world,namespace);}
    public synchronized RuntimeCasResult worldActionCompareAndSet(UUID world,String namespace,String id,long expected,String payload,long at)throws SQLException{return write(world,namespace,id,expected,payload,at,false,true);}

    /** The explicit restore receipt and existing activation transition are committed together. No Native call runs here. */
    public synchronized void worldActivationResume(UUID world,UUID activation,long expected,String payload,UUID operation,String receipt,long at)throws SQLException{
        String ns="world_package_activations_v1",requests="world_package_restore_requests_v1";
        validateKey(world,ns,activation.toString(),expected,payload);validateKey(world,requests,operation.toString(),0,receipt);
        if(expected<1||receipt.length()>8192)throw new IllegalArgumentException("RESTORE_REQUEST_INVALID");
        beginWrite();
        try{
            if(select(world,requests,operation.toString()).isPresent()||select(world,ns,operation.toString()).isPresent())throw new IllegalStateException("RESTORE_REQUEST_REUSED");
            try(var q=connection.prepareStatement("SELECT COUNT(*) FROM mineagent_runtime_records WHERE world_id=? AND namespace=?")){q.setString(1,world.toString());q.setString(2,requests);try(var r=q.executeQuery()){if(r.next()&&r.getLong(1)>=65536)throw new IllegalStateException("RESTORE_REQUEST_BUDGET");}}
            try(var q=connection.prepareStatement("UPDATE mineagent_runtime_records SET revision=?,payload=?,updated_at=? WHERE world_id=? AND namespace=? AND record_id=? AND revision=? AND deleted=0")){q.setLong(1,expected+1);q.setString(2,payload);q.setLong(3,at);q.setString(4,world.toString());q.setString(5,ns);q.setString(6,activation.toString());q.setLong(7,expected);if(q.executeUpdate()!=1)throw new IllegalStateException("RESTORE_REQUEST_STALE");}
            try(var q=connection.prepareStatement("INSERT INTO mineagent_runtime_records(world_id,namespace,record_id,revision,payload,updated_at,deleted) VALUES(?,?,?,1,?,?,0)")){q.setString(1,world.toString());q.setString(2,requests);q.setString(3,operation.toString());q.setString(4,receipt);q.setLong(5,at);q.executeUpdate();}
            connection.commit();
        }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}finally{endWrite();}
    }

    public synchronized RuntimeCasResult compareAndSet(
            UUID worldId,
            String namespace,
            String recordId,
            long expectedRevision,
            String payload,
            long updatedAtEpochMillis
    ) throws SQLException {
        return write(worldId, namespace, recordId, expectedRevision, payload, updatedAtEpochMillis, false);
    }

    public synchronized RuntimeCasResult delete(
            UUID worldId,
            String namespace,
            String recordId,
            long expectedRevision,
            long updatedAtEpochMillis
    ) throws SQLException {
        return write(worldId, namespace, recordId, expectedRevision, "", updatedAtEpochMillis, true);
    }

    private RuntimeCasResult write(
            UUID worldId,
            String namespace,
            String recordId,
            long expectedRevision,
            String payload,
            long updatedAtEpochMillis,
            boolean deleted
    ) throws SQLException {
        return write(worldId,namespace,recordId,expectedRevision,payload,updatedAtEpochMillis,deleted,false);
    }
    private RuntimeCasResult write(UUID worldId,String namespace,String recordId,long expectedRevision,String payload,long updatedAtEpochMillis,boolean deleted,boolean worldAction)throws SQLException{
        return write(worldId,namespace,recordId,expectedRevision,payload,updatedAtEpochMillis,deleted,worldAction,false,null);
    }
    private RuntimeCasResult write(UUID worldId,String namespace,String recordId,long expectedRevision,String payload,long updatedAtEpochMillis,boolean deleted,boolean worldAction,boolean taskCreation,TaskBudgetLineage.Parent taskParent)throws SQLException{
        validateKey(worldId, namespace, recordId, expectedRevision, payload);
        beginWrite();
        try {
            RuntimeRecord current = select(worldId, namespace, recordId).orElse(null);
            if (current == null) {
                if (expectedRevision != 0 || deleted) {
                    connection.rollback();
                    return new RuntimeCasResult(false, missing(worldId, namespace, recordId));
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO mineagent_runtime_records
                        (world_id, namespace, record_id, revision, payload, updated_at, deleted)
                        VALUES (?, ?, ?, 1, ?, ?, 0)
                        """)) {
                    insert.setString(1, worldId.toString());
                    insert.setString(2, namespace);
                    insert.setString(3, recordId);
                    insert.setString(4, payload);
                    insert.setLong(5, updatedAtEpochMillis);
                    insert.executeUpdate();
                }
                if(taskCreation)TaskBudgetLineage.create(connection,worldId,recordId,taskParent);
                if(worldAction)projectWorldAction(worldId,namespace,recordId,1,payload);
                connection.commit();
                return new RuntimeCasResult(true,
                        new RuntimeRecord(worldId, namespace, recordId, 1, payload, updatedAtEpochMillis, false));
            }
            if (current.revision() != expectedRevision) {
                connection.rollback();
                return new RuntimeCasResult(false, current);
            }
            long nextRevision = expectedRevision + 1;
            int updated;
            try (var update = connection.prepareStatement("""
                    UPDATE mineagent_runtime_records
                    SET revision = ?, payload = ?, updated_at = ?, deleted = ?
                    WHERE world_id = ? AND namespace = ? AND record_id = ? AND revision = ?
                    """)) {
                update.setLong(1, nextRevision);
                update.setString(2, payload);
                update.setLong(3, updatedAtEpochMillis);
                update.setBoolean(4, deleted);
                update.setString(5, worldId.toString());
                update.setString(6, namespace);
                update.setString(7, recordId);
                update.setLong(8, expectedRevision);
                updated = update.executeUpdate();
            }
            if (updated != 1) {
                connection.rollback();
                RuntimeRecord raced = select(worldId, namespace, recordId).orElse(current);
                return new RuntimeCasResult(false, raced);
            }
            if(worldAction)projectWorldAction(worldId,namespace,recordId,nextRevision,payload);
            connection.commit();
            return new RuntimeCasResult(true,
                    new RuntimeRecord(worldId, namespace, recordId, nextRevision,
                            payload, updatedAtEpochMillis, deleted));
        } catch (SQLException|RuntimeException|Error failure) {
            connection.rollback();
            if(failure instanceof SQLException sql){String message=java.util.Objects.toString(sql.getMessage(),"");for(String code:java.util.List.of("PACKAGE_JOB_ROW_BUDGET","PACKAGE_JOB_BYTE_BUDGET","PACKAGE_JOB_ACTIVE_BUDGET","PACKAGE_JOB_RETAINED_ID"))if(message.contains(code))throw new IllegalStateException(code,sql);}
            throw failure;
        } finally {
            endWrite();
        }
    }
    private void projectWorldAction(UUID world,String namespace,String id,long revision,String payload)throws SQLException{
        try{WorldActionRetention.project(connection,world,namespace,id,revision,payload,true);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new SQLException("WORLD_ACTION_INDEX",failure);}
    }

    public synchronized Optional<RuntimeRecord> get(UUID worldId, String namespace, String recordId) throws SQLException {
        validateKey(worldId, namespace, recordId, 0, "");
        return select(worldId, namespace, recordId).filter(record -> !record.deleted());
    }
    /** Needed to distinguish an uncommitted create from a deliberately deleted idempotent object. */
    public synchronized Optional<RuntimeRecord> getIncludingDeleted(UUID worldId,String namespace,String recordId)throws SQLException{
        validateKey(worldId,namespace,recordId,0,"");return select(worldId,namespace,recordId);
    }

    private Optional<RuntimeRecord> select(UUID worldId, String namespace, String recordId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision, payload, updated_at, deleted
                FROM mineagent_runtime_records
                WHERE world_id = ? AND namespace = ? AND record_id = ?
                """)) {
            statement.setString(1, worldId.toString());
            statement.setString(2, namespace);
            statement.setString(3, recordId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(new RuntimeRecord(
                        worldId, namespace, recordId, result.getLong(1), result.getString(2),
                        result.getLong(3), result.getBoolean(4))) : Optional.empty();
            }
        }
    }

    public synchronized List<RuntimeRecord> list(UUID worldId, String namespace) throws SQLException {
        validateKey(worldId, namespace, "list", 0, "");
        var records = new ArrayList<RuntimeRecord>();
        try (var statement = connection.prepareStatement("""
                SELECT record_id, revision, payload, updated_at
                FROM mineagent_runtime_records
                WHERE world_id = ? AND namespace = ? AND deleted = 0
                ORDER BY updated_at DESC, record_id
                """)) {
            statement.setString(1, worldId.toString());
            statement.setString(2, namespace);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(new RuntimeRecord(worldId, namespace, result.getString(1), result.getLong(2),
                            result.getString(3), result.getLong(4), false));
                }
            }
        }
        return List.copyOf(records);
    }

    public synchronized void appendAudit(
            UUID worldId,
            String actor,
            String action,
            String target,
            String payload,
            long createdAtEpochMillis
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mineagent_runtime_audit(world_id, actor, action, target, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, worldId.toString());
            statement.setString(2, required(actor, "actor"));
            statement.setString(3, required(action, "action"));
            statement.setString(4, required(target, "target"));
            statement.setString(5, payload == null ? "" : payload);
            statement.setLong(6, createdAtEpochMillis);
            statement.executeUpdate();
        }
    }

    public synchronized List<AuditRecord> audit(UUID worldId, int limit) throws SQLException {
        if (worldId == null || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("invalid audit query");
        }
        var records = new ArrayList<AuditRecord>();
        try (var statement = connection.prepareStatement("""
                SELECT sequence, actor, action, target, payload, created_at
                FROM mineagent_runtime_audit WHERE world_id = ? ORDER BY sequence DESC LIMIT ?
                """)) {
            statement.setString(1, worldId.toString());
            statement.setInt(2, limit);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(new AuditRecord(result.getLong(1), worldId, result.getString(2),
                            result.getString(3), result.getString(4), result.getString(5), result.getLong(6)));
                }
            }
        }
        java.util.Collections.reverse(records);
        return List.copyOf(records);
    }

    public synchronized void pruneAudit(UUID worldId, long oldestCreatedAt, long maximumBytes) throws SQLException {
        if (worldId == null || oldestCreatedAt < 0 || maximumBytes < 1) {
            throw new IllegalArgumentException("invalid audit retention policy");
        }
        beginWrite();
        try {
            try (var expired = connection.prepareStatement(
                    "DELETE FROM mineagent_runtime_audit WHERE world_id = ? AND created_at < ?")) {
                expired.setString(1, worldId.toString());
                expired.setLong(2, oldestCreatedAt);
                expired.executeUpdate();
            }
            long bytes = auditBytes(worldId);
            if (bytes > maximumBytes) {
                try (var oldest = connection.prepareStatement("""
                        SELECT sequence,
                          length(CAST(actor AS BLOB)) + length(CAST(action AS BLOB))
                          + length(CAST(target AS BLOB)) + length(CAST(payload AS BLOB)) + 48
                        FROM mineagent_runtime_audit
                        WHERE world_id = ? ORDER BY sequence ASC
                        """);
                     var delete = connection.prepareStatement(
                             "DELETE FROM mineagent_runtime_audit WHERE sequence = ?")) {
                    oldest.setString(1, worldId.toString());
                    try (var records = oldest.executeQuery()) {
                        while (bytes > maximumBytes && records.next()) {
                            delete.setLong(1, records.getLong(1));
                            delete.executeUpdate();
                            bytes -= records.getLong(2);
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            endWrite();
        }
    }

    private long auditBytes(UUID worldId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(length(CAST(actor AS BLOB)) + length(CAST(action AS BLOB))
                  + length(CAST(target AS BLOB)) + length(CAST(payload AS BLOB)) + 48), 0)
                FROM mineagent_runtime_audit WHERE world_id = ?
                """)) {
            statement.setString(1, worldId.toString());
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static void validateKey(UUID worldId, String namespace, String recordId, long revision, String payload) {
        if (worldId == null || revision < 0 || payload == null
                || namespace == null || !namespace.matches("[a-z][a-z0-9_.-]{0,63}")
                || recordId == null || recordId.isBlank() || recordId.length() > 128) {
            throw new IllegalArgumentException("invalid runtime record key");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value;
    }

    private static RuntimeRecord missing(UUID worldId, String namespace, String recordId) {
        return new RuntimeRecord(worldId, namespace, recordId, 0, "", 0, true);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
