package dev.mineagent.runtime.core.persistence;

import java.sql.*;
import java.util.*;

/** Original job payloads/IDs stay authoritative. Metadata and retention accounting follow their CAS transaction. */
public final class PackageJobRetention {
    public static final int MAX_HOT=4096,MAX_ROWS=131072;
    public static final long MAX_BYTES=512L*1024*1024,PATCH_RESERVE=16L*1024*1024;
    public static final Set<String> NAMESPACES=Set.of("package_generation_jobs","package_ui_patch_jobs","package_world_patch_jobs_v1","agent_ui_task_links","package_ui_patch_rebuilds_v1");
    private static final String SCOPES="'package_generation_jobs','package_ui_patch_jobs','package_world_patch_jobs_v1','agent_ui_task_links','package_ui_patch_rebuilds_v1'";
    private PackageJobRetention(){}
    public record Query(UUID owner,String state,String archive,UUID packageId,String hash,UUID task,Long intent){
        public Query{if(state==null||!state.matches("[A-Z_]{1,32}")||!Set.of("ALL","ACTIVE","ARCHIVED").contains(archive)||hash==null||!hash.matches("(?:[a-f0-9]{64})?")||intent!=null&&intent<1)throw new IllegalArgumentException("PACKAGE_HISTORY_FILTER");}
        public static Query all(UUID owner){return new Query(owner,"ALL","ALL",null,"",null,null);}
        public static Query active(){return new Query(null,"ALL","ACTIVE",null,"",null,null);}
    }
    public record Usage(long total,long active,long archived,long payloadBytes,long reservedBytes,int maximumRows,int maximumActive,long maximumBytes){}
    private static String value(String prefix,String path){return "CASE WHEN "+prefix+"deleted=1 THEN '' ELSE COALESCE(json_extract("+prefix+"payload,'$."+path+"'),'') END";}
    private static String expression(String prefix,String field){
        String ns=prefix+"namespace",link=ns+"='agent_ui_task_links'",rebuild=ns+"='package_ui_patch_rebuilds_v1'";
        return switch(field){
            case "owner"->"CASE WHEN "+link+" THEN "+value(prefix,"owner")+" WHEN "+rebuild+" THEN "+value(prefix,"failedJob.ownerPlayerId")+" ELSE "+value(prefix,"ownerPlayerId")+" END";
            case "agent"->"CASE WHEN "+link+" THEN "+value(prefix,"agent")+" WHEN "+rebuild+" THEN "+value(prefix,"failedJob.agentId")+" ELSE "+value(prefix,"agentId")+" END";
            case "task"->"CASE WHEN "+link+" THEN "+value(prefix,"parentTaskId")+" WHEN "+rebuild+" THEN "+value(prefix,"failedJob.taskId")+" ELSE "+value(prefix,"taskId")+" END";
            case "intent"->"CASE WHEN "+link+" THEN "+value(prefix,"parentIntent")+" WHEN "+rebuild+" THEN "+value(prefix,"failedJob.taskIntentRevision")+" ELSE "+value(prefix,"taskIntentRevision")+" END";
            case "package_id"->"CASE WHEN "+rebuild+" THEN "+value(prefix,"failedJob.base.packageId")+" WHEN "+ns+" IN ('package_ui_patch_jobs','package_world_patch_jobs_v1') THEN "+value(prefix,"base.packageId")+" ELSE "+value(prefix,"packageId")+" END";
            case "state"->"CASE WHEN "+rebuild+" THEN 'EVIDENCE' ELSE "+value(prefix,"state")+" END";
            default->throw new IllegalArgumentException("PACKAGE_INDEX_FIELD");
        };
    }
    private static String hot(String prefix){String ns=prefix+"namespace",state=expression(prefix,"state");return "("+prefix+"deleted=0 AND (CASE WHEN "+ns+"='package_generation_jobs' THEN "+state+"='GENERATING' WHEN "+ns+"='agent_ui_task_links' THEN "+state+" NOT IN ('FAILED','STALE','COMPLETED') WHEN "+ns+" IN ('package_ui_patch_jobs','package_world_patch_jobs_v1') THEN "+state+" IN ('PENDING','READY','APPLYING','ROLLING_BACK') ELSE 0 END))";}
    private static String reserve(String prefix){return "CASE WHEN NOT "+hot(prefix)+" THEN 0 WHEN "+prefix+"namespace IN ('package_ui_patch_jobs','package_world_patch_jobs_v1') AND "+expression(prefix,"state")+"='PENDING' THEN "+PATCH_RESERVE+" ELSE 4096 END";}
    private static String projection(String p){return p+"world_id,"+p+"namespace,"+p+"record_id,"+expression(p,"owner")+","+expression(p,"agent")+","+expression(p,"task")+","+expression(p,"intent")+","+expression(p,"package_id")+","+expression(p,"state")+",CASE WHEN "+hot(p)+" THEN 0 ELSE 1 END,"+p+"revision,"+p+"updated_at,length(CAST("+p+"payload AS BLOB)),"+reserve(p)+","+value(p,"canonicalSha256")+","+value(p,"base.canonicalSha256")+","+value(p,"candidate.canonicalSha256")+",CASE WHEN "+p+"deleted=1 THEN 0 ELSE COALESCE(json_extract("+p+"payload,'$.headRevision'),0) END";}

    static void initialize(Connection db,UUID world)throws SQLException{
        try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_package_job_index_v1(world TEXT NOT NULL,namespace TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,task TEXT NOT NULL,intent INTEGER NOT NULL,package_id TEXT NOT NULL,state TEXT NOT NULL,archived INTEGER NOT NULL,revision INTEGER NOT NULL,updated_at INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,reserved_bytes INTEGER NOT NULL,canonical TEXT NOT NULL,base_hash TEXT NOT NULL,candidate_hash TEXT NOT NULL,head_revision INTEGER NOT NULL,PRIMARY KEY(world,namespace,id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_package_job_usage_v1(world TEXT NOT NULL,namespace TEXT NOT NULL,row_count INTEGER NOT NULL,hot INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,reserved_bytes INTEGER NOT NULL,PRIMARY KEY(world,namespace))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_package_job_owner_v1 ON mineagent_package_job_index_v1(world,namespace,owner,updated_at DESC,id)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_package_job_hot_v1 ON mineagent_package_job_index_v1(world,namespace,archived,state)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_package_job_target_v1 ON mineagent_package_job_index_v1(world,namespace,package_id,owner)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_package_job_parent_v1 ON mineagent_package_job_index_v1(world,namespace,task,intent)");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_usage_insert_v1 AFTER INSERT ON mineagent_package_job_index_v1 BEGIN INSERT INTO mineagent_package_job_usage_v1 VALUES(NEW.world,NEW.namespace,1,1-NEW.archived,NEW.payload_bytes,NEW.reserved_bytes) ON CONFLICT(world,namespace) DO UPDATE SET row_count=row_count+1,hot=hot+1-NEW.archived,payload_bytes=payload_bytes+NEW.payload_bytes,reserved_bytes=reserved_bytes+NEW.reserved_bytes; END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_usage_update_v1 AFTER UPDATE ON mineagent_package_job_index_v1 BEGIN UPDATE mineagent_package_job_usage_v1 SET hot=hot+OLD.archived-NEW.archived,payload_bytes=payload_bytes+NEW.payload_bytes-OLD.payload_bytes,reserved_bytes=reserved_bytes+NEW.reserved_bytes-OLD.reserved_bytes WHERE world=NEW.world AND namespace=NEW.namespace; END");
            String fields="world,namespace,id,owner,agent,task,intent,package_id,state,archived,revision,updated_at,payload_bytes,reserved_bytes,canonical,base_hash,candidate_hash,head_revision";
            String project="INSERT INTO mineagent_package_job_index_v1("+fields+") VALUES("+projection("NEW.")+") ON CONFLICT(world,namespace,id) DO UPDATE SET owner=excluded.owner,agent=excluded.agent,task=excluded.task,intent=excluded.intent,package_id=excluded.package_id,state=excluded.state,archived=excluded.archived,revision=excluded.revision,updated_at=excluded.updated_at,payload_bytes=excluded.payload_bytes,reserved_bytes=excluded.reserved_bytes,canonical=excluded.canonical,base_hash=excluded.base_hash,candidate_hash=excluded.candidate_hash,head_revision=excluded.head_revision;";
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_project_insert_v1 AFTER INSERT ON mineagent_runtime_records WHEN NEW.namespace IN ("+SCOPES+") BEGIN "+project+" END");
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_project_update_v1 AFTER UPDATE ON mineagent_runtime_records WHEN NEW.namespace IN ("+SCOPES+") BEGIN "+project+" END");
            // No physical delete of retained operations: absence must never be interpreted as permission to repeat a call.
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_keep_ids_v1 BEFORE DELETE ON mineagent_runtime_records WHEN OLD.namespace IN ("+SCOPES+") BEGIN SELECT RAISE(ABORT,'PACKAGE_JOB_RETAINED_ID'); END");
            try(var q=db.prepareStatement("INSERT OR IGNORE INTO mineagent_package_job_index_v1("+fields+") SELECT "+projection("r.")+" FROM mineagent_runtime_records r WHERE r.world_id=? AND r.namespace IN ("+SCOPES+")")){q.setString(1,world.toString());q.executeUpdate();}
            String current="COALESCE((SELECT payload_bytes+reserved_bytes FROM mineagent_package_job_usage_v1 WHERE world=NEW.world_id AND namespace=NEW.namespace),0)",next="length(CAST(NEW.payload AS BLOB))+"+reserve("NEW.");
            String hotCount="COALESCE((SELECT hot FROM mineagent_package_job_usage_v1 WHERE world=NEW.world_id AND namespace=NEW.namespace),0)";
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_limit_insert_v1 BEFORE INSERT ON mineagent_runtime_records WHEN NEW.namespace IN ("+SCOPES+") BEGIN SELECT CASE WHEN COALESCE((SELECT row_count FROM mineagent_package_job_usage_v1 WHERE world=NEW.world_id AND namespace=NEW.namespace),0)>="+MAX_ROWS+" THEN RAISE(ABORT,'PACKAGE_JOB_ROW_BUDGET') END; SELECT CASE WHEN "+current+"+"+next+">"+MAX_BYTES+" THEN RAISE(ABORT,'PACKAGE_JOB_BYTE_BUDGET') END; SELECT CASE WHEN "+hot("NEW.")+" AND "+hotCount+">="+MAX_HOT+" THEN RAISE(ABORT,'PACKAGE_JOB_ACTIVE_BUDGET') END; END");
            String old="COALESCE((SELECT payload_bytes+reserved_bytes FROM mineagent_package_job_index_v1 WHERE world=OLD.world_id AND namespace=OLD.namespace AND id=OLD.record_id),length(CAST(OLD.payload AS BLOB)))";
            String grace="NOT "+hot("NEW.")+" AND length(CAST(NEW.payload AS BLOB))<=length(CAST(OLD.payload AS BLOB))+4096";
            s.execute("CREATE TRIGGER IF NOT EXISTS mineagent_package_job_limit_update_v1 BEFORE UPDATE ON mineagent_runtime_records WHEN NEW.namespace IN ("+SCOPES+") BEGIN SELECT CASE WHEN NEW.deleted<>OLD.deleted THEN RAISE(ABORT,'PACKAGE_JOB_RETAINED_ID') END; SELECT CASE WHEN "+current+"-("+old+")+("+next+")>"+MAX_BYTES+" AND ("+next+")>("+old+") AND NOT ("+grace+") THEN RAISE(ABORT,'PACKAGE_JOB_BYTE_BUDGET') END; SELECT CASE WHEN "+hot("NEW.")+" AND NOT "+hot("OLD.")+" AND "+hotCount+">="+MAX_HOT+" THEN RAISE(ABORT,'PACKAGE_JOB_ACTIVE_BUDGET') END; END");
            s.execute("COMMIT");
        }catch(SQLException|RuntimeException failure){try{s.execute("ROLLBACK");}catch(SQLException ignored){}throw failure;}}
    }
    private static String where(String ns,Query q){
        if(!NAMESPACES.contains(ns))throw new IllegalArgumentException("PACKAGE_HISTORY_CATEGORY");
        return "i.world=? AND i.namespace=?"+(q.owner()==null?"":" AND i.owner=?")+(q.state().equals("ALL")?"":" AND i.state=?")+(q.archive().equals("ALL")?"":" AND i.archived="+(q.archive().equals("ACTIVE")?0:1))+(q.packageId()==null?"":" AND i.package_id=?")+(q.hash().isEmpty()?"":" AND (i.canonical=? OR i.base_hash=? OR (i.head_revision>0 AND i.candidate_hash=?))")+(q.task()==null?"":" AND i.task=?")+(q.intent()==null?"":" AND i.intent=?");
    }
    private static int bind(PreparedStatement s,UUID world,String ns,Query q)throws SQLException{
        int i=1;s.setString(i++,world.toString());s.setString(i++,ns);if(q.owner()!=null)s.setString(i++,q.owner().toString());if(!q.state().equals("ALL"))s.setString(i++,q.state());if(q.packageId()!=null)s.setString(i++,q.packageId().toString());if(!q.hash().isEmpty()){s.setString(i++,q.hash());s.setString(i++,q.hash());s.setString(i++,q.hash());}if(q.task()!=null)s.setString(i++,q.task().toString());if(q.intent()!=null)s.setLong(i++,q.intent());return i;
    }
    static List<RuntimeRecord> page(Connection db,UUID world,String ns,Query query,int offset,int limit)throws SQLException{
        if(offset<0||limit<1||limit>256)throw new IllegalArgumentException("PACKAGE_HISTORY_PAGE");var rows=new ArrayList<RuntimeRecord>();
        try(var q=db.prepareStatement("SELECT r.record_id,r.revision,r.payload,r.updated_at FROM mineagent_package_job_index_v1 i JOIN mineagent_runtime_records r ON r.world_id=i.world AND r.namespace=i.namespace AND r.record_id=i.id WHERE "+where(ns,query)+" AND r.deleted=0 ORDER BY i.updated_at DESC,i.id LIMIT ? OFFSET ?")){int p=bind(q,world,ns,query);q.setInt(p++,limit);q.setInt(p,offset);try(var r=q.executeQuery()){while(r.next())rows.add(new RuntimeRecord(world,ns,r.getString(1),r.getLong(2),r.getString(3),r.getLong(4),false));}}return List.copyOf(rows);
    }
    static long count(Connection db,UUID world,String ns,Query query)throws SQLException{
        try(var q=db.prepareStatement("SELECT COUNT(*) FROM mineagent_package_job_index_v1 i JOIN mineagent_runtime_records r ON r.world_id=i.world AND r.namespace=i.namespace AND r.record_id=i.id WHERE "+where(ns,query)+" AND r.deleted=0")){bind(q,world,ns,query);try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}
    }
    static Usage usage(Connection db,UUID world,String ns,UUID owner)throws SQLException{
        var query=Query.all(owner);try(var q=db.prepareStatement("SELECT COUNT(*),COALESCE(SUM(1-i.archived),0),COALESCE(SUM(i.payload_bytes),0),COALESCE(SUM(i.reserved_bytes),0) FROM mineagent_package_job_index_v1 i WHERE "+where(ns,query))){bind(q,world,ns,query);try(var r=q.executeQuery()){if(!r.next())throw new SQLException("PACKAGE_JOB_USAGE");return new Usage(r.getLong(1),r.getLong(2),r.getLong(1)-r.getLong(2),r.getLong(3),r.getLong(4),MAX_ROWS,MAX_HOT,MAX_BYTES);}}
    }
    static boolean ownedHead(Connection db,UUID world,String ns,UUID owner,UUID pkg,long revision,String hash)throws SQLException{
        boolean generation=ns.equals("package_generation_jobs");if(!generation&&!Set.of("package_ui_patch_jobs","package_world_patch_jobs_v1").contains(ns))throw new IllegalArgumentException("PACKAGE_HISTORY_CATEGORY");
        String condition=generation?"i.state='PUBLISHED' AND i.canonical=? AND json_extract(r.payload,'$.packageRevision')<=?":"i.head_revision>0 AND i.head_revision<=? AND ((i.state='APPLIED' AND i.candidate_hash=?) OR (i.state='ROLLED_BACK' AND i.base_hash=?))";
        try(var q=db.prepareStatement("SELECT 1 FROM mineagent_package_job_index_v1 i JOIN mineagent_runtime_records r ON r.world_id=i.world AND r.namespace=i.namespace AND r.record_id=i.id WHERE i.world=? AND i.namespace=? AND i.owner=? AND i.package_id=? AND r.deleted=0 AND "+condition+" LIMIT 1")){q.setString(1,world.toString());q.setString(2,ns);q.setString(3,owner.toString());q.setString(4,pkg.toString());if(generation){q.setString(5,hash);q.setLong(6,revision);}else{q.setLong(5,revision);q.setString(6,hash);q.setString(7,hash);}try(var r=q.executeQuery()){return r.next();}}
    }
}
