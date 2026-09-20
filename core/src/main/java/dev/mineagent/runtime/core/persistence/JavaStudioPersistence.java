package dev.mineagent.runtime.core.persistence;

import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.packages.JavaStudioMetadata.*;
import dev.mineagent.runtime.api.packages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

/** Unified head/history/source-link/receipt transaction; no code or task execution here. */
final class JavaStudioPersistence {
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Source(String entry,boolean java,boolean client,Map<String,CodeDraft.SourceRef> refs){}
    static void initialize(Connection db)throws SQLException {try(var s=db.createStatement()){
        s.execute("CREATE TABLE IF NOT EXISTS mineagent_java_studio_v1(package_id TEXT PRIMARY KEY,world TEXT NOT NULL,owner TEXT NOT NULL,canonical TEXT NOT NULL,payload TEXT NOT NULL)");
        s.execute("CREATE INDEX IF NOT EXISTS mineagent_java_studio_owner_v1 ON mineagent_java_studio_v1(world,owner,package_id)");
        s.execute("CREATE TABLE IF NOT EXISTS mineagent_java_studio_ops_v1(world TEXT NOT NULL,owner TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,owner,id))");
    }}
    static Link link(Connection db,UUID world,UUID owner,UUID pkg)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM mineagent_java_studio_v1 WHERE world=? AND owner=? AND package_id=?")){q.setString(1,world.toString());q.setString(2,owner.toString());q.setString(3,pkg.toString());try(var r=q.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Link.class):null;}}}
    static Receipt receipt(Connection db,UUID world,UUID owner,UUID id)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM mineagent_java_studio_ops_v1 WHERE world=? AND owner=? AND id=?")){q.setString(1,world.toString());q.setString(2,owner.toString());q.setString(3,id.toString());try(var r=q.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Receipt.class):null;}}}
    static Receipt publish(Connection db,Input input,String payload,long now)throws Exception {
        db.setAutoCommit(false);try{
            var old=receipt(db,input.world(),input.owner(),input.operation());if(old!=null){if(!old.input().equals(input))throw new IllegalStateException("JAVA_STUDIO_OPERATION_REUSED");db.commit();return old;}
            if(input.draftRevision()<1||input.expectedPackageRevision()<0||!input.sourceHash().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("JAVA_STUDIO_INPUT");
            CodeDraft draft;try(var q=db.prepareStatement("SELECT payload,revision FROM mineagent_runtime_records WHERE world_id=? AND namespace='code_drafts' AND record_id=? AND deleted=0")){q.setString(1,input.world().toString());q.setString(2,input.draft().toString());try(var r=q.executeQuery()){if(!r.next()||r.getLong(2)!=input.draftRevision())throw new IllegalStateException("JAVA_STUDIO_DRAFT_CHANGED");draft=JSON.readValue(r.getString(1),CodeDraft.class);if(!draft.ownerPlayerId().equals(input.owner())||!draft.packageId().equals(input.packageId())||!CodeDraftSources.fingerprint(draft).equals(input.sourceHash()))throw new IllegalStateException("JAVA_STUDIO_DRAFT_CHANGED");}}
            var candidate=JSON.readValue(payload,RuntimePackage.class);var source=source(candidate,draft.path().startsWith("client/"),CodeDraftSources.java(draft.path()));String candidateSource=CodeDraftSources.studioFingerprint(source.entry(),source.refs(),candidate.dependencies());if(!candidate.packageId().equals(input.packageId())||!candidateSource.equals(input.sourceHash())||candidate.enabled()||candidate.revision()!=input.expectedPackageRevision()+1)throw new IllegalStateException("JAVA_STUDIO_PACKAGE_CHANGED");
            RuntimeRecord current=null;try(var q=db.prepareStatement("SELECT payload,revision,updated_at FROM mineagent_runtime_records WHERE world_id=? AND namespace=? AND record_id=? AND deleted=0")){q.setString(1,RuntimePackageLibrary.GLOBAL_LIBRARY_ID.toString());q.setString(2,PackageLibraryHistory.NS);q.setString(3,input.packageId().toString());try(var r=q.executeQuery()){if(r.next())current=new RuntimeRecord(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,PackageLibraryHistory.NS,input.packageId().toString(),r.getLong(2),r.getString(1),r.getLong(3),false);}}
            if(current==null?input.expectedPackageRevision()!=0:current.revision()!=input.expectedPackageRevision())throw new IllegalStateException("JAVA_STUDIO_PACKAGE_CHANGED");
            var before=PackageLibraryHistory.usage(db);
            if(current==null){try(var q=db.prepareStatement("INSERT INTO mineagent_runtime_records VALUES(?,?,?,1,?,?,0)")){q.setString(1,RuntimePackageLibrary.GLOBAL_LIBRARY_ID.toString());q.setString(2,PackageLibraryHistory.NS);q.setString(3,input.packageId().toString());q.setString(4,payload);q.setLong(5,now);q.executeUpdate();}}
            else{try(var q=db.prepareStatement("UPDATE mineagent_runtime_records SET revision=?,payload=?,updated_at=? WHERE world_id=? AND namespace=? AND record_id=? AND revision=? AND deleted=0")){q.setLong(1,candidate.revision());q.setString(2,payload);q.setLong(3,now);q.setString(4,RuntimePackageLibrary.GLOBAL_LIBRARY_ID.toString());q.setString(5,PackageLibraryHistory.NS);q.setString(6,input.packageId().toString());q.setLong(7,current.revision());if(q.executeUpdate()!=1)throw new IllegalStateException("JAVA_STUDIO_PACKAGE_CHANGED");}}
            String provenance=source.client()?(source.java()?"CLIENT_JAVA_STUDIO_SOURCE":"CLIENT_RHINO_STUDIO_SOURCE"):source.java()?"JAVA_STUDIO_SOURCE":"RHINO_STUDIO_SOURCE";var record=new RuntimeRecord(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,PackageLibraryHistory.NS,input.packageId().toString(),candidate.revision(),payload,now,false);PackageLibraryHistory.record(db,current,"OBSERVED_PREVIOUS_HEAD",now);PackageLibraryHistory.record(db,record,provenance,now);PackageLibraryHistory.check(before,PackageLibraryHistory.usage(db));
            var link=new Link(input.world(),input.owner(),input.packageId(),input.draft(),input.draftRevision(),input.sourceHash(),candidate.canonicalSha256(),candidate.revision());
            try(var q=db.prepareStatement("INSERT INTO mineagent_java_studio_v1 VALUES(?,?,?,?,?) ON CONFLICT(package_id) DO UPDATE SET canonical=excluded.canonical,payload=excluded.payload WHERE world=excluded.world AND owner=excluded.owner")){q.setString(1,input.packageId().toString());q.setString(2,input.world().toString());q.setString(3,input.owner().toString());q.setString(4,candidate.canonicalSha256());q.setString(5,JSON.writeValueAsString(link));if(q.executeUpdate()!=1)throw new IllegalStateException("JAVA_STUDIO_NOT_OWNED");}
            try(var q=db.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM mineagent_java_studio_ops_v1")){r.next();if(r.getLong(1)>=131072)throw new IllegalStateException("JAVA_STUDIO_RECEIPT_BUDGET");}
            var result=new Receipt(input,"SOURCE_PUBLISHED_NOT_EXECUTED",candidate.canonicalSha256(),candidate.revision());try(var q=db.prepareStatement("INSERT INTO mineagent_java_studio_ops_v1 VALUES(?,?,?,?)")){q.setString(1,input.world().toString());q.setString(2,input.owner().toString());q.setString(3,input.operation().toString());q.setString(4,JSON.writeValueAsString(result));q.executeUpdate();}
            db.commit();return result;
        }catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    private static Source source(RuntimePackage candidate,boolean client,boolean java){
        if(client){var refs=new TreeMap<String,CodeDraft.SourceRef>();if(java){var plan=ClientJavaPlan.inspect(candidate);plan.sources().forEach((path,ref)->refs.put(path,new CodeDraft.SourceRef(ref.sha256(),Math.toIntExact(ref.size()))));return new Source(plan.entrypoint(),true,true,Map.copyOf(refs));}var plan=ClientScriptPlan.inspect(candidate);plan.modules().forEach((path,ref)->refs.put(path,new CodeDraft.SourceRef(ref.sha256(),Math.toIntExact(ref.size()))));return new Source(plan.entrypoint(),false,true,Map.copyOf(refs));}
        if(RuntimeStudioPlan.java(candidate)!=java)throw new IllegalStateException("STUDIO_LANGUAGE_CHANGED");var entry=RuntimeStudioPlan.source(candidate);return new Source(entry.path(),java,false,RuntimeStudioPlan.refs(candidate));
    }
}
