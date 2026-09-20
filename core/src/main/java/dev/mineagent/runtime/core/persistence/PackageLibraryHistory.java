package dev.mineagent.runtime.core.persistence;

import dev.mineagent.runtime.core.packages.RuntimePackageLibrary;
import java.sql.*;
import java.util.*;

/** Exact stored library revisions, not reconstructed patch manifests or permission to restore a version. */
public final class PackageLibraryHistory {
    public static final String NS="runtime_packages_v2",IMPORTS="explicit_package_owners_v1";
    public static final long MAX_HEADS=131072,MAX_VERSIONS=1048576,MAX_HEAD_BYTES=512L*1024*1024,MAX_VERSION_BYTES=2L*1024*1024*1024;
    private PackageLibraryHistory(){}
    public record Usage(long heads,long headBytes,long versions,long versionBytes,long reservedRows,long reservedBytes){}
    public record Version(UUID packageId,long revision,String payload,String payloadHash,String canonical,String provenance,long observedAt){}
    public record VersionSummary(long revision,String hash,String payloadHash,boolean enabled,String version,String origin,String provenance,long observedAt){}
    static void initialize(Connection db)throws SQLException{
        try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_library_usage_v1(id INTEGER PRIMARY KEY CHECK(id=1),heads INTEGER NOT NULL,head_bytes INTEGER NOT NULL,versions INTEGER NOT NULL,version_bytes INTEGER NOT NULL,reserved_rows INTEGER NOT NULL,reserved_bytes INTEGER NOT NULL)");
            s.execute("INSERT OR IGNORE INTO mineagent_library_usage_v1 VALUES(1,0,0,0,0,0,0)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_library_heads_v1(id TEXT PRIMARY KEY,name TEXT NOT NULL,version TEXT NOT NULL,origin TEXT NOT NULL,enabled INTEGER NOT NULL,revision INTEGER NOT NULL,canonical TEXT NOT NULL,payload_bytes INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_library_head_page_v1 ON mineagent_library_heads_v1(updated_at DESC,id)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_library_versions_v1(package_id TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,payload_hash TEXT NOT NULL,canonical TEXT NOT NULL,enabled INTEGER NOT NULL,version TEXT NOT NULL,origin TEXT NOT NULL,provenance TEXT NOT NULL,observed_at INTEGER NOT NULL,PRIMARY KEY(package_id,revision))");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_head_usage_insert_v1 AFTER INSERT ON mineagent_library_heads_v1 BEGIN UPDATE mineagent_library_usage_v1 SET heads=heads+1,head_bytes=head_bytes+NEW.payload_bytes,reserved_rows=reserved_rows+NEW.enabled,reserved_bytes=reserved_bytes+NEW.enabled*(NEW.payload_bytes+4096) WHERE id=1; END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_head_usage_update_v1 AFTER UPDATE ON mineagent_library_heads_v1 BEGIN UPDATE mineagent_library_usage_v1 SET head_bytes=head_bytes+NEW.payload_bytes-OLD.payload_bytes,reserved_rows=reserved_rows+NEW.enabled-OLD.enabled,reserved_bytes=reserved_bytes+NEW.enabled*(NEW.payload_bytes+4096)-OLD.enabled*(OLD.payload_bytes+4096) WHERE id=1; END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_version_usage_v1 AFTER INSERT ON mineagent_library_versions_v1 BEGIN UPDATE mineagent_library_usage_v1 SET versions=versions+1,version_bytes=version_bytes+length(CAST(NEW.payload AS BLOB)) WHERE id=1; END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_version_keep_update_v1 BEFORE UPDATE ON mineagent_library_versions_v1 BEGIN SELECT RAISE(ABORT,'PACKAGE_VERSION_IMMUTABLE'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_version_keep_delete_v1 BEFORE DELETE ON mineagent_library_versions_v1 BEGIN SELECT RAISE(ABORT,'PACKAGE_VERSION_IMMUTABLE'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_keep_id_v1 BEFORE DELETE ON mineagent_runtime_records WHEN OLD.world_id='"+RuntimePackageLibrary.GLOBAL_LIBRARY_ID+"' AND OLD.namespace IN ('"+NS+"','"+IMPORTS+"') BEGIN SELECT RAISE(ABORT,'PACKAGE_LIBRARY_RETAINED_ID'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_keep_tombstone_v1 BEFORE UPDATE ON mineagent_runtime_records WHEN OLD.world_id='"+RuntimePackageLibrary.GLOBAL_LIBRARY_ID+"' AND OLD.namespace IN ('"+NS+"','"+IMPORTS+"') AND NEW.deleted<>OLD.deleted BEGIN SELECT RAISE(ABORT,'PACKAGE_LIBRARY_RETAINED_ID'); END");
            String scope="NEW.world_id='"+RuntimePackageLibrary.GLOBAL_LIBRARY_ID+"' AND NEW.namespace='"+NS+"' AND NEW.deleted=0";
            String project="INSERT INTO mineagent_library_heads_v1 VALUES(NEW.record_id,COALESCE(json_extract(NEW.payload,'$.name'),''),COALESCE(json_extract(NEW.payload,'$.version'),''),COALESCE(json_extract(NEW.payload,'$.origin'),''),CASE WHEN json_extract(NEW.payload,'$.enabled')=1 THEN 1 ELSE 0 END,NEW.revision,COALESCE(json_extract(NEW.payload,'$.canonicalSha256'),''),length(CAST(NEW.payload AS BLOB)),NEW.updated_at) ON CONFLICT(id) DO UPDATE SET name=excluded.name,version=excluded.version,origin=excluded.origin,enabled=excluded.enabled,revision=excluded.revision,canonical=excluded.canonical,payload_bytes=excluded.payload_bytes,updated_at=excluded.updated_at;";
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_head_insert_v1 AFTER INSERT ON mineagent_runtime_records WHEN "+scope+" BEGIN "+project+" END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_library_head_update_v1 AFTER UPDATE ON mineagent_runtime_records WHEN "+scope+" BEGIN "+project+" END");
            // Invalid JSON remains quarantined by the library; it cannot become a usable catalog entry.
            s.execute("INSERT OR IGNORE INTO mineagent_library_heads_v1 SELECT record_id,COALESCE(json_extract(payload,'$.name'),''),COALESCE(json_extract(payload,'$.version'),''),COALESCE(json_extract(payload,'$.origin'),''),CASE WHEN json_extract(payload,'$.enabled')=1 THEN 1 ELSE 0 END,revision,COALESCE(json_extract(payload,'$.canonicalSha256'),''),length(CAST(payload AS BLOB)),updated_at FROM mineagent_runtime_records WHERE world_id='"+RuntimePackageLibrary.GLOBAL_LIBRARY_ID+"' AND namespace='"+NS+"' AND deleted=0 AND json_valid(payload)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_import_claims_v1(id TEXT PRIMARY KEY,owner TEXT NOT NULL,hash TEXT NOT NULL,revision INTEGER NOT NULL,payload_bytes INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_import_claim_owner_v1 ON mineagent_import_claims_v1(owner,id)");
            String imports="INSERT INTO mineagent_import_claims_v1 VALUES(NEW.record_id,json_extract(NEW.payload,'$.owner'),json_extract(NEW.payload,'$.hash'),json_extract(NEW.payload,'$.revision'),length(CAST(NEW.payload AS BLOB))) ON CONFLICT(id) DO UPDATE SET owner=excluded.owner,hash=excluded.hash,revision=excluded.revision,payload_bytes=excluded.payload_bytes;";
            String importScope="NEW.world_id='"+RuntimePackageLibrary.GLOBAL_LIBRARY_ID+"' AND NEW.namespace='"+IMPORTS+"' AND NEW.deleted=0";
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_import_claim_insert_v1 AFTER INSERT ON mineagent_runtime_records WHEN "+importScope+" BEGIN "+imports+" END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_import_claim_update_v1 AFTER UPDATE ON mineagent_runtime_records WHEN "+importScope+" BEGIN "+imports+" END");
            s.execute("INSERT OR IGNORE INTO mineagent_import_claims_v1 SELECT record_id,json_extract(payload,'$.owner'),json_extract(payload,'$.hash'),json_extract(payload,'$.revision'),length(CAST(payload AS BLOB)) FROM mineagent_runtime_records WHERE world_id='"+RuntimePackageLibrary.GLOBAL_LIBRARY_ID+"' AND namespace='"+IMPORTS+"' AND deleted=0 AND json_valid(payload)");
            PackageAssetPersistence.initialize(db);JavaStudioPersistence.initialize(db);
            s.execute("COMMIT");
        }catch(SQLException|RuntimeException failure){try{s.execute("ROLLBACK");}catch(SQLException ignored){}throw failure;}}
    }
    static Usage usage(Connection db)throws SQLException{try(var q=db.prepareStatement("SELECT heads,head_bytes,versions,version_bytes,reserved_rows,reserved_bytes FROM mineagent_library_usage_v1 WHERE id=1");var r=q.executeQuery()){if(!r.next())throw new SQLException("PACKAGE_LIBRARY_USAGE");return new Usage(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6));}}
    static void check(Usage before,Usage after){
        boolean stopping=after.reservedRows()<before.reservedRows()&&after.heads()==before.heads();
        if(after.heads()>MAX_HEADS&&after.heads()>before.heads()||after.headBytes()>MAX_HEAD_BYTES&&after.headBytes()>before.headBytes()&&!(stopping&&after.headBytes()<=before.headBytes()+4096))throw new IllegalStateException("PACKAGE_LIBRARY_HEAD_BUDGET");
        if(after.versions()+after.reservedRows()>MAX_VERSIONS&&after.versions()+after.reservedRows()>before.versions()+before.reservedRows()||after.versionBytes()+after.reservedBytes()>MAX_VERSION_BYTES&&after.versionBytes()+after.reservedBytes()>before.versionBytes()+before.reservedBytes())throw new IllegalStateException("PACKAGE_LIBRARY_VERSION_BUDGET");
    }
    static void record(Connection db,RuntimeRecord record,String provenance,long at)throws Exception{
        if(record==null)return;
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.payload());
        if(!record.recordId().equals(json.path("packageId").asText())||record.revision()!=json.path("revision").asLong())throw new IllegalStateException("PACKAGE_VERSION_CONTEXT");
        String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(record.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var q=db.prepareStatement("INSERT OR IGNORE INTO mineagent_library_versions_v1 VALUES(?,?,?,?,?,?,?,?,?,?)")){q.setString(1,record.recordId());q.setLong(2,record.revision());q.setString(3,record.payload());q.setString(4,hash);q.setString(5,json.path("canonicalSha256").asText());q.setBoolean(6,json.path("enabled").asBoolean());q.setString(7,json.path("version").asText());q.setString(8,json.path("origin").asText());q.setString(9,provenance);q.setLong(10,at);q.executeUpdate();}
        try(var q=db.prepareStatement("SELECT payload_hash FROM mineagent_library_versions_v1 WHERE package_id=? AND revision=?")){q.setString(1,record.recordId());q.setLong(2,record.revision());try(var r=q.executeQuery()){if(!r.next()||!hash.equals(r.getString(1)))throw new IllegalStateException("PACKAGE_VERSION_CONFLICT");}}
    }
    static Version version(Connection db,UUID id,long revision)throws SQLException{
        try(var q=db.prepareStatement("SELECT payload,payload_hash,canonical,provenance,observed_at FROM mineagent_library_versions_v1 WHERE package_id=? AND revision=?")){q.setString(1,id.toString());q.setLong(2,revision);try(var r=q.executeQuery()){return r.next()?new Version(id,revision,r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)):null;}}
    }
    static List<VersionSummary> versions(Connection db,UUID id,int offset,int limit)throws SQLException{
        if(offset<0||limit<1||limit>16)throw new IllegalArgumentException("PACKAGE_VERSION_PAGE");var rows=new ArrayList<VersionSummary>();
        try(var q=db.prepareStatement("SELECT revision,canonical,payload_hash,enabled,version,origin,provenance,observed_at FROM mineagent_library_versions_v1 WHERE package_id=? ORDER BY revision DESC LIMIT ? OFFSET ?")){q.setString(1,id.toString());q.setInt(2,limit);q.setInt(3,offset);try(var r=q.executeQuery()){while(r.next())rows.add(new VersionSummary(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8)));}}return List.copyOf(rows);
    }
    public record Catalog(List<UUID> ids,long total,int offset,int nextOffset,boolean more){}
    static Catalog catalog(Connection db,UUID world,UUID owner,String search,int offset,int limit)throws SQLException{
        if(search==null||search.length()>128||offset<0||limit<1||limit>32)throw new IllegalArgumentException("PACKAGE_CATALOG_PAGE");
        String claim="EXISTS(SELECT 1 FROM mineagent_package_job_index_v1 j JOIN mineagent_runtime_records r ON r.world_id=j.world AND r.namespace=j.namespace AND r.record_id=j.id WHERE j.world=? AND j.owner=? AND j.package_id=h.id AND r.deleted=0 AND ((j.namespace='package_generation_jobs' AND j.state='PUBLISHED' AND j.canonical=h.canonical AND json_extract(r.payload,'$.packageRevision')<=h.revision) OR (j.namespace IN ('package_ui_patch_jobs','package_world_patch_jobs_v1') AND j.head_revision>0 AND j.head_revision<=h.revision AND ((j.state='APPLIED' AND j.candidate_hash=h.canonical) OR (j.state='ROLLED_BACK' AND j.base_hash=h.canonical))))) OR EXISTS(SELECT 1 FROM mineagent_import_claims_v1 c WHERE c.id=h.id AND c.owner=? AND c.hash=h.canonical AND c.revision<=h.revision AND h.origin='EXPLICIT_IMPORT') OR EXISTS(SELECT 1 FROM mineagent_asset_copies_v1 c WHERE c.package_id=h.id AND c.world=? AND c.owner=? AND c.canonical=h.canonical AND h.origin='REUSED') OR EXISTS(SELECT 1 FROM mineagent_java_studio_v1 s WHERE s.package_id=h.id AND s.world=? AND s.owner=? AND s.canonical=h.canonical)";
        String where="(instr(lower(h.name),lower(?))>0 OR instr(lower(h.id),lower(?))>0 OR EXISTS(SELECT 1 FROM mineagent_asset_aliases_v1 a WHERE a.owner=? AND a.package_id=h.id AND instr(lower(a.name),lower(?))>0)) AND ("+claim+")";
        long total;try(var q=db.prepareStatement("SELECT COUNT(*) FROM mineagent_library_heads_v1 h WHERE "+where)){bindCatalog(q,world,owner,search);try(var r=q.executeQuery()){total=r.next()?r.getLong(1):0;}}
        var ids=new ArrayList<UUID>();try(var q=db.prepareStatement("SELECT h.id FROM mineagent_library_heads_v1 h WHERE "+where+" ORDER BY h.updated_at DESC,h.id LIMIT ? OFFSET ?")){bindCatalog(q,world,owner,search);q.setInt(12,limit);q.setInt(13,offset);try(var r=q.executeQuery()){while(r.next())ids.add(UUID.fromString(r.getString(1)));}}
        return new Catalog(List.copyOf(ids),total,offset,offset+ids.size(),total>offset+ids.size());
    }
    private static void bindCatalog(PreparedStatement q,UUID world,UUID owner,String search)throws SQLException{q.setString(1,search);q.setString(2,search);q.setString(3,owner.toString());q.setString(4,search);q.setString(5,world.toString());q.setString(6,owner.toString());q.setString(7,owner.toString());q.setString(8,world.toString());q.setString(9,owner.toString());q.setString(10,world.toString());q.setString(11,owner.toString());}
}
