package dev.mineagent.runtime.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.packages.PackageAssetMetadata.*;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Metadata, selected immutable asset versions and COPY provenance commit on the same library connection. */
final class PackageAssetPersistence {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String GLOBAL=RuntimePackageLibrary.GLOBAL_LIBRARY_ID.toString();
    static void initialize(Connection db)throws SQLException {
        try(var s=db.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_asset_aliases_v1(owner TEXT NOT NULL,package_id TEXT NOT NULL,name TEXT NOT NULL,revision INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(owner,package_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_asset_shelf_v1(id TEXT PRIMARY KEY,owner TEXT NOT NULL,source_package TEXT NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL,created INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_asset_shelf_owner_v1 ON mineagent_asset_shelf_v1(owner,created DESC,id)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_asset_copies_v1(package_id TEXT PRIMARY KEY,owner TEXT NOT NULL,world TEXT NOT NULL,canonical TEXT NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_asset_copy_owner_v1 ON mineagent_asset_copies_v1(world,owner,package_id)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_asset_operations_v1(world TEXT NOT NULL,owner TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(world,owner,id))");
            for(String table:List.of("mineagent_asset_copies_v1","mineagent_asset_operations_v1")){
                s.execute("CREATE TRIGGER IF NOT EXISTS "+table+"_immutable_update BEFORE UPDATE ON "+table+" BEGIN SELECT RAISE(ABORT,'PACKAGE_ASSET_RECEIPT_IMMUTABLE'); END");
                s.execute("CREATE TRIGGER IF NOT EXISTS "+table+"_immutable_delete BEFORE DELETE ON "+table+" BEGIN SELECT RAISE(ABORT,'PACKAGE_ASSET_RECEIPT_IMMUTABLE'); END");
            }
        }
    }
    static Alias alias(Connection db,UUID owner,UUID pkg)throws SQLException {try(var q=db.prepareStatement("SELECT name,revision FROM mineagent_asset_aliases_v1 WHERE owner=? AND package_id=?")){q.setString(1,owner.toString());q.setString(2,pkg.toString());try(var r=q.executeQuery()){return r.next()?new Alias(r.getString(1),r.getLong(2)):new Alias("",0);}}}
    static Shelf shelf(Connection db,UUID owner,UUID id)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM mineagent_asset_shelf_v1 WHERE owner=? AND id=?")){q.setString(1,owner.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("PACKAGE_ASSET_SHELF_MISSING");return JSON.readValue(r.getString(1),Shelf.class);}}}
    static Page<Shelf> shelves(Connection db,UUID owner,boolean active,int offset,int limit)throws Exception {
        if(offset<0||limit<1||limit>8)throw new IllegalArgumentException("PACKAGE_ASSET_PAGE");long count;
        try(var q=db.prepareStatement("SELECT COUNT(*) FROM mineagent_asset_shelf_v1 WHERE owner=? AND active=?")){q.setString(1,owner.toString());q.setBoolean(2,active);try(var r=q.executeQuery()){r.next();count=r.getLong(1);}}
        var rows=new ArrayList<Shelf>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_asset_shelf_v1 WHERE owner=? AND active=? ORDER BY created DESC,id LIMIT ? OFFSET ?")){q.setString(1,owner.toString());q.setBoolean(2,active);q.setInt(3,limit);q.setInt(4,offset);try(var r=q.executeQuery()){while(r.next())rows.add(JSON.readValue(r.getString(1),Shelf.class));}}
        return new Page<>(rows,count,offset,offset+rows.size(),offset+rows.size()<count);
    }
    static Derivation derivation(Connection db,UUID owner,UUID pkg)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM mineagent_asset_copies_v1 WHERE package_id=? AND owner=?")){q.setString(1,pkg.toString());q.setString(2,owner.toString());try(var r=q.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Derivation.class):null;}}}
    static boolean owns(Connection db,UUID world,UUID owner,UUID pkg,long revision,String canonical)throws SQLException {
        try(var q=db.prepareStatement("SELECT 1 FROM mineagent_asset_copies_v1 WHERE package_id=? AND world=? AND owner=? AND canonical=?")){q.setString(1,pkg.toString());q.setString(2,world.toString());q.setString(3,owner.toString());q.setString(4,canonical);try(var r=q.executeQuery()){return revision>=1&&r.next();}}
    }
    static Receipt receipt(Connection db,UUID world,UUID owner,UUID id)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM mineagent_asset_operations_v1 WHERE world=? AND owner=? AND id=?")){q.setString(1,world.toString());q.setString(2,owner.toString());q.setString(3,id.toString());try(var r=q.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Receipt.class):null;}}}
    private static PackageLibraryHistory.Version source(Connection db,Input input)throws Exception {
        if(input.shelf()!=null){var s=shelf(db,input.owner(),input.shelf());if(!s.active()||s.revision()!=input.expectedRevision()||!s.packageId().equals(input.source())||s.packageRevision()!=input.sourceRevision()||!s.canonical().equals(input.sourceHash()))throw new IllegalStateException("PACKAGE_ASSET_SHELF_STALE");}
        else try(var q=db.prepareStatement("SELECT revision,payload FROM mineagent_runtime_records WHERE world_id=? AND namespace=? AND record_id=? AND deleted=0")){
            q.setString(1,GLOBAL);q.setString(2,PackageLibraryHistory.NS);q.setString(3,input.source().toString());try(var r=q.executeQuery()){if(!r.next()||(input.action().equals("COPY_VERSION")?r.getLong(1)!=input.expectedRevision():r.getLong(1)!=input.sourceRevision()||!JSON.readTree(r.getString(2)).path("canonicalSha256").asText().equals(input.sourceHash())))throw new IllegalStateException("PACKAGE_ASSET_SOURCE_CHANGED");}
        }
        var version=PackageLibraryHistory.version(db,input.source(),input.sourceRevision());if(version==null||!version.canonical().equals(input.sourceHash())||!RuntimePackageCanonicalizer.sha256(version.payload()).equals(version.payloadHash()))throw new IllegalStateException("PACKAGE_ASSET_VERSION_UNAVAILABLE");
        if(input.shelf()!=null&&!shelf(db,input.owner(),input.shelf()).payloadHash().equals(version.payloadHash()))throw new IllegalStateException("PACKAGE_ASSET_VERSION_UNAVAILABLE");return version;
    }
    static Receipt metadata(Connection db,Input input,long now)throws Exception {
        db.setAutoCommit(false);try{
            var old=receipt(db,input.world(),input.owner(),input.operation());if(old!=null){if(!old.input().equals(input))throw new IllegalStateException("PACKAGE_ASSET_OPERATION_REUSED");db.commit();return old;}
            Receipt result;
            if(Set.of("WITHDRAW_ASSET","RESTORE_ASSET").contains(input.action())){
                var s=shelf(db,input.owner(),input.shelf());if(s.revision()!=input.expectedRevision()||!s.packageId().equals(input.source())||s.packageRevision()!=input.sourceRevision()||!s.canonical().equals(input.sourceHash()))throw new IllegalStateException("PACKAGE_ASSET_SHELF_STALE");
                var next=new Shelf(s.id(),s.owner(),s.sourceWorld(),s.packageId(),s.packageRevision(),s.canonical(),s.payloadHash(),s.version(),s.sourceName(),s.origin(),input.action().equals("RESTORE_ASSET"),s.revision()+1,s.createdAt());writeShelf(db,next,false);result=new Receipt(input,input.action().equals("RESTORE_ASSET")?"SHELF_RESTORED":"SHELF_WITHDRAWN",null,"",s.id(),next.revision(),null);
            }else{
                var snapshot=source(db,input);var pkg=JSON.readValue(snapshot.payload(),RuntimePackage.class);
                if(input.action().equals("RENAME")){
                    var alias=alias(db,input.owner(),input.source());if(alias.revision()!=input.expectedRevision())throw new IllegalStateException("PACKAGE_ALIAS_STALE");
                    if(alias.revision()==0&&count(db,"mineagent_asset_aliases_v1")>=131072)throw new IllegalStateException("PACKAGE_ALIAS_BUDGET");
                    try(var q=db.prepareStatement("INSERT INTO mineagent_asset_aliases_v1 VALUES(?,?,?,1,?) ON CONFLICT(owner,package_id) DO UPDATE SET name=excluded.name,revision=mineagent_asset_aliases_v1.revision+1,updated=excluded.updated")){q.setString(1,input.owner().toString());q.setString(2,input.source().toString());q.setString(3,input.name());q.setLong(4,now);q.executeUpdate();}
                    result=new Receipt(input,"LIBRARY_NAME_CHANGED",input.source(),input.sourceHash(),null,alias.revision()+1,null);
                }else if(input.action().equals("SAVE_ASSET")){
                    UUID id=UUID.nameUUIDFromBytes((input.owner()+":"+input.source()+":"+input.sourceRevision()+":"+input.sourceHash()).getBytes(StandardCharsets.UTF_8));Shelf existing=null;
                    try(var q=db.prepareStatement("SELECT payload FROM mineagent_asset_shelf_v1 WHERE id=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){if(r.next())existing=JSON.readValue(r.getString(1),Shelf.class);}}
                    if(existing!=null&&!existing.active())throw new IllegalStateException("PACKAGE_ASSET_SHELF_WITHDRAWN");
                    var s=existing!=null?existing:new Shelf(id,input.owner(),input.world(),pkg.packageId(),pkg.revision(),pkg.canonicalSha256(),snapshot.payloadHash(),pkg.version(),pkg.name(),pkg.origin().name(),true,1,now);
                    if(existing==null)writeShelf(db,s,true);result=new Receipt(input,existing==null?"ASSET_VERSION_SAVED":"ASSET_VERSION_ALREADY_SAVED",pkg.packageId(),pkg.canonicalSha256(),s.id(),s.revision(),null);
                }else throw new IllegalArgumentException("PACKAGE_ASSET_ACTION");
            }
            saveReceipt(db,result,now);db.commit();return result;
        }catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    static Receipt copy(Connection db,Input input,String payload,long now)throws Exception {
        if(!Set.of("COPY","COPY_VERSION","REUSE_ASSET").contains(input.action()))throw new IllegalArgumentException("PACKAGE_COPY_ACTION");
        db.setAutoCommit(false);try{
            var old=receipt(db,input.world(),input.owner(),input.operation());if(old!=null){if(!old.input().equals(input))throw new IllegalStateException("PACKAGE_ASSET_OPERATION_REUSED");db.commit();return old;}
            var source=source(db,input);var candidate=JSON.readValue(payload,RuntimePackage.class);
            if(!candidate.packageId().equals(input.targetId())||!candidate.name().equals(input.name())||candidate.origin()!=dev.mineagent.runtime.api.packages.PackageOrigin.REUSED||candidate.enabled()||candidate.revision()!=1)throw new IllegalStateException("PACKAGE_COPY_TARGET");
            var base=JSON.readValue(source.payload(),RuntimePackage.class);
            if(candidate.type()!=base.type()||!candidate.version().equals(base.version())||candidate.activationMode()!=base.activationMode()||!candidate.dependencies().equals(base.dependencies())||!candidate.permissions().equals(base.permissions())||!candidate.entrypoints().equals(base.entrypoints())||!candidate.definitions().equals(base.definitions())||!candidate.resources().equals(base.resources())||!Objects.equals(candidate.nativeCompatibility(),base.nativeCompatibility()))throw new IllegalStateException("PACKAGE_COPY_ASSET_PARITY");
            var before=PackageLibraryHistory.usage(db);
            try(var q=db.prepareStatement("INSERT INTO mineagent_runtime_records(world_id,namespace,record_id,revision,payload,updated_at,deleted) VALUES(?,?,?,1,?,?,0)")){q.setString(1,GLOBAL);q.setString(2,PackageLibraryHistory.NS);q.setString(3,candidate.packageId().toString());q.setString(4,payload);q.setLong(5,now);q.executeUpdate();}
            var saved=new RuntimeRecord(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,PackageLibraryHistory.NS,candidate.packageId().toString(),1,payload,now,false);PackageLibraryHistory.record(db,saved,"EXPLICIT_ASSET_COPY",now);PackageLibraryHistory.check(before,PackageLibraryHistory.usage(db));
            var provenance=new Derivation(input.owner(),input.world(),input.operation(),candidate.packageId(),input.source(),input.sourceRevision(),input.sourceHash(),source.payloadHash(),input.action(),input.shelf(),candidate.canonicalSha256(),candidate.name(),"ASSET_BYTES_UNCHANGED; NEW_PACKAGE_ID_NAME_ORIGIN; NO_WORLD_STATE_OR_APPROVALS",now);
            String raw=JSON.writeValueAsString(provenance);if(raw.getBytes(StandardCharsets.UTF_8).length>8192||count(db,"mineagent_asset_copies_v1")>=131072||bytes(db,"mineagent_asset_copies_v1")+raw.getBytes(StandardCharsets.UTF_8).length>128L*1024*1024)throw new IllegalStateException("PACKAGE_COPY_BUDGET");
            try(var q=db.prepareStatement("INSERT INTO mineagent_asset_copies_v1 VALUES(?,?,?,?,?,?)")){q.setString(1,candidate.packageId().toString());q.setString(2,input.owner().toString());q.setString(3,input.world().toString());q.setString(4,candidate.canonicalSha256());q.setString(5,raw);q.setInt(6,raw.getBytes(StandardCharsets.UTF_8).length);q.executeUpdate();}
            var result=new Receipt(input,"ASSET_COPY_CREATED",candidate.packageId(),candidate.canonicalSha256(),input.shelf(),1,provenance);saveReceipt(db,result,now);db.commit();return result;
        }catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    private static void writeShelf(Connection db,Shelf s,boolean insert)throws Exception {
        String raw=JSON.writeValueAsString(s);int actual=raw.getBytes(StandardCharsets.UTF_8).length,size=actual+128;
        if(!insert)try(var q=db.prepareStatement("SELECT bytes FROM mineagent_asset_shelf_v1 WHERE id=? AND owner=?")){q.setString(1,s.id().toString());q.setString(2,s.owner().toString());try(var r=q.executeQuery()){if(!r.next()||actual>r.getInt(1))throw new IllegalStateException("PACKAGE_SHELF_BUDGET");size=r.getInt(1);}}
        if(actual>8192||insert&&(count(db,"mineagent_asset_shelf_v1")>=131072||bytes(db,"mineagent_asset_shelf_v1")+size>128L*1024*1024))throw new IllegalStateException("PACKAGE_SHELF_BUDGET");
        try(var q=db.prepareStatement(insert?"INSERT INTO mineagent_asset_shelf_v1 VALUES(?,?,?,?,?,?,?,?)":"UPDATE mineagent_asset_shelf_v1 SET active=?,revision=?,payload=?,bytes=? WHERE id=? AND owner=? AND revision=?")){
            if(insert){q.setString(1,s.id().toString());q.setString(2,s.owner().toString());q.setString(3,s.packageId().toString());q.setBoolean(4,s.active());q.setLong(5,s.revision());q.setString(6,raw);q.setInt(7,size);q.setLong(8,s.createdAt());}
            else{q.setBoolean(1,s.active());q.setLong(2,s.revision());q.setString(3,raw);q.setInt(4,size);q.setString(5,s.id().toString());q.setString(6,s.owner().toString());q.setLong(7,s.revision()-1);}if(q.executeUpdate()!=1)throw new IllegalStateException("PACKAGE_SHELF_CAS");
        }
    }
    private static void saveReceipt(Connection db,Receipt r,long now)throws Exception {String raw=JSON.writeValueAsString(r);int size=raw.getBytes(StandardCharsets.UTF_8).length;if(size>16384||count(db,"mineagent_asset_operations_v1")>=1048576||bytes(db,"mineagent_asset_operations_v1")+size>512L*1024*1024)throw new IllegalStateException("PACKAGE_ASSET_OPERATION_BUDGET");try(var q=db.prepareStatement("INSERT INTO mineagent_asset_operations_v1 VALUES(?,?,?,?,?,?)")){q.setString(1,r.input().world().toString());q.setString(2,r.input().owner().toString());q.setString(3,r.input().operation().toString());q.setString(4,raw);q.setInt(5,size);q.setLong(6,now);q.executeUpdate();}}
    private static long count(Connection db,String table)throws SQLException{return aggregate(db,table,"COUNT(*)");}
    private static long bytes(Connection db,String table)throws SQLException{return aggregate(db,table,"COALESCE(SUM(bytes),0)");}
    private static long aggregate(Connection db,String table,String value)throws SQLException{try(var q=db.createStatement();var r=q.executeQuery("SELECT "+value+" FROM "+table)){r.next();return r.getLong(1);}}
}
