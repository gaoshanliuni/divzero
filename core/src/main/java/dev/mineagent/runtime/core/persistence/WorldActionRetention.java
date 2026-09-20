package dev.mineagent.runtime.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.WorldActionJournal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Original records remain authoritative; metadata and generation ownership share the original CAS transaction. */
public final class WorldActionRetention {
    public static final String BATCHES="world_action_batches_v1",PLANNING="world_action_planning_v1";
    public static final long MAX_ROWS=131072,MAX_BYTES=512L*1024*1024,RECEIPT_RESERVE=2L*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private WorldActionRetention(){}
    public record Usage(long total,long active,long payloadBytes,long reservedBytes,long maximumRows,long maximumBytes){}

    static void initialize(Connection db,UUID world)throws Exception{
        try(var statement=db.createStatement()){statement.execute("BEGIN IMMEDIATE");try{
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_world_action_index_v1(world TEXT NOT NULL,id TEXT NOT NULL,task TEXT NOT NULL,intent INTEGER NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,round INTEGER NOT NULL,state TEXT NOT NULL,active INTEGER NOT NULL,action_count INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,reserved_bytes INTEGER NOT NULL,PRIMARY KEY(world,id),UNIQUE(world,task,intent,round))");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_world_action_active_v1 ON mineagent_world_action_index_v1(world,active,state)");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_world_action_owner_v1 ON mineagent_world_action_index_v1(world,owner,task)");
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_world_generation_index_v1(world TEXT NOT NULL,operation TEXT NOT NULL,batch TEXT NOT NULL,PRIMARY KEY(world,operation))");
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_world_planning_index_v1(world TEXT NOT NULL,id TEXT NOT NULL,task TEXT NOT NULL,intent INTEGER NOT NULL,pending INTEGER NOT NULL,uncertain INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,PRIMARY KEY(world,id))");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_world_planning_pending_v1 ON mineagent_world_planning_index_v1(world,pending,uncertain)");
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_world_action_usage_v1(world TEXT NOT NULL,namespace TEXT NOT NULL,row_count INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,reserved_bytes INTEGER NOT NULL,PRIMARY KEY(world,namespace))");
            for(String ns:List.of(BATCHES,PLANNING)){String table=ns.equals(BATCHES)?"mineagent_world_action_index_v1":"mineagent_world_planning_index_v1";
                try(var query=db.prepareStatement("SELECT record_id,revision,payload FROM mineagent_runtime_records r WHERE world_id=? AND namespace=? AND deleted=0 AND NOT EXISTS(SELECT 1 FROM "+table+" i WHERE i.world=r.world_id AND i.id=r.record_id)")){query.setString(1,world.toString());query.setString(2,ns);try(var rows=query.executeQuery()){while(rows.next())project(db,world,ns,rows.getString(1),rows.getLong(2),rows.getString(3),false);}}
            }statement.execute("COMMIT");
        }catch(Exception|Error failure){try{statement.execute("ROLLBACK");}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}}
    }
    static void project(Connection db,UUID world,String namespace,String id,long revision,String payload,boolean enforce)throws Exception{
        boolean batch=namespace.equals(BATCHES);if(!batch&&!namespace.equals(PLANNING))throw new IllegalArgumentException("WORLD_ACTION_NAMESPACE");String table=batch?"mineagent_world_action_index_v1":"mineagent_world_planning_index_v1";
        long previousBytes=0,previousReserve=0;boolean exists=false,previousActive=false;try(var query=db.prepareStatement("SELECT payload_bytes,"+(batch?"reserved_bytes,active":"0,pending")+" FROM "+table+" WHERE world=? AND id=?")){query.setString(1,world.toString());query.setString(2,id);try(var rows=query.executeQuery()){if(rows.next()){exists=true;previousBytes=rows.getLong(1);previousReserve=rows.getLong(2);previousActive=rows.getBoolean(3);}}}
        long bytes=payload.getBytes(StandardCharsets.UTF_8).length,reserve=0;boolean active;WorldActionJournal.Batch b=null;WorldActionJournal.Planning p=null;
        if(batch){b=JSON.readValue(payload,WorldActionJournal.Batch.class);if(!world.equals(b.worldId())||!id.equals(b.batchId().toString())||b.revision()!=revision||b.intent()<1||b.round()<0||b.round()>=16||b.actions().isEmpty()||b.actions().size()>256||b.cursor()<0||b.cursor()>b.actions().size()||b.receipts().size()>256||!Set.of("READY","EXECUTING","VERIFIED","COMPLETED","FAILED","INTERRUPTED").contains(b.state()))throw new IllegalStateException("WORLD_ACTION_RECORD");active=Set.of("READY","EXECUTING","VERIFIED").contains(b.state());reserve=b.state().equals("EXECUTING")?RECEIPT_RESERVE:0;}
        else{p=JSON.readValue(payload,WorldActionJournal.Planning.class);if(!id.equals(p.taskId()+"|"+p.intent())||p.revision()!=revision||p.intent()<1||p.attempts()<1||p.attempts()>16||p.pending()&&p.uncertain())throw new IllegalStateException("WORLD_PLANNING_RECORD");active=p.pending();}
        var usage=usage(db,world,namespace);long delta=bytes+reserve-previousBytes-previousReserve;
        if(enforce){if(!exists&&usage.total()>=MAX_ROWS)throw new IllegalStateException("WORLD_ACTION_RETAINED_ROW_BUDGET");if(active&&!previousActive&&usage.active()>=4096)throw new IllegalStateException("WORLD_ACTION_ACTIVE_BUDGET");
            boolean settling=exists&&!active&&bytes-previousBytes<=4096;if(delta>0&&usage.payloadBytes()+usage.reservedBytes()+delta>MAX_BYTES&&!settling)throw new IllegalStateException("WORLD_ACTION_STORAGE_BUDGET");}
        if(batch){
            try(var query=db.prepareStatement("INSERT INTO mineagent_world_action_index_v1 VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,active=excluded.active,payload_bytes=excluded.payload_bytes,reserved_bytes=excluded.reserved_bytes")){query.setString(1,world.toString());query.setString(2,id);query.setString(3,b.taskId().toString());query.setLong(4,b.intent());query.setString(5,b.owner().toString());query.setString(6,b.agentId().toString());query.setInt(7,b.round());query.setString(8,b.state());query.setBoolean(9,active);query.setInt(10,b.actions().size());query.setLong(11,bytes);query.setLong(12,reserve);query.executeUpdate();}
            // Immutable actions: point ownership lookup must survive cursor advance and cache retirement.
            if(!exists){for(int i=0;i<b.actions().size();i++)if(b.actions().get(i).tool().equals("create_world_package")){var operation=UUID.nameUUIDFromBytes((id+"|"+i).getBytes(StandardCharsets.UTF_8));try(var query=db.prepareStatement("INSERT INTO mineagent_world_generation_index_v1(world,operation,batch) VALUES(?,?,?)")){query.setString(1,world.toString());query.setString(2,operation.toString());query.setString(3,id);query.executeUpdate();}}}
        }else try(var query=db.prepareStatement("INSERT INTO mineagent_world_planning_index_v1 VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET pending=excluded.pending,uncertain=excluded.uncertain,payload_bytes=excluded.payload_bytes")){query.setString(1,world.toString());query.setString(2,id);query.setString(3,p.taskId().toString());query.setLong(4,p.intent());query.setBoolean(5,p.pending());query.setBoolean(6,p.uncertain());query.setLong(7,bytes);query.executeUpdate();}
        try(var query=db.prepareStatement("INSERT INTO mineagent_world_action_usage_v1 VALUES(?,?,?,?,?) ON CONFLICT(world,namespace) DO UPDATE SET row_count=row_count+excluded.row_count,payload_bytes=payload_bytes+excluded.payload_bytes,reserved_bytes=reserved_bytes+excluded.reserved_bytes")){query.setString(1,world.toString());query.setString(2,namespace);query.setInt(3,exists?0:1);query.setLong(4,bytes-previousBytes);query.setLong(5,reserve-previousReserve);query.executeUpdate();}
    }
    static Usage usage(Connection db,UUID world,String ns)throws SQLException{
        boolean batch=ns.equals(BATCHES);if(!batch&&!ns.equals(PLANNING))throw new IllegalArgumentException("WORLD_ACTION_NAMESPACE");long total=0,bytes=0,reserved=0,active;
        try(var query=db.prepareStatement("SELECT row_count,payload_bytes,reserved_bytes FROM mineagent_world_action_usage_v1 WHERE world=? AND namespace=?")){query.setString(1,world.toString());query.setString(2,ns);try(var rows=query.executeQuery()){if(rows.next()){total=rows.getLong(1);bytes=rows.getLong(2);reserved=rows.getLong(3);}}}
        try(var query=db.prepareStatement("SELECT COUNT(*) FROM "+(batch?"mineagent_world_action_index_v1 WHERE world=? AND active=1":"mineagent_world_planning_index_v1 WHERE world=? AND pending=1"))){query.setString(1,world.toString());try(var rows=query.executeQuery()){active=rows.next()?rows.getLong(1):0;}}
        return new Usage(total,active,bytes,reserved,MAX_ROWS,MAX_BYTES);
    }
    static List<RuntimeRecord> rows(Connection db,UUID world,UUID task,Long intent,UUID owner,String kind,int offset,int limit)throws SQLException{
        if(offset<0||limit<1||limit>MAX_ROWS||!Set.of("ALL","ACTIVE","ATTENTION","PLANNING_ALL","PLANNING_PENDING","PLANNING_ATTENTION").contains(kind))throw new IllegalArgumentException("WORLD_ACTION_PAGE");boolean plan=kind.startsWith("PLANNING");String table=plan?"mineagent_world_planning_index_v1":"mineagent_world_action_index_v1",ns=plan?PLANNING:BATCHES;
        String filter=task==null?"":" AND i.task=?";if(intent!=null)filter+=" AND i.intent=?";if(owner!=null){if(plan)throw new IllegalArgumentException("WORLD_PLANNING_OWNER_QUERY");filter+=" AND i.owner=?";}
        if(kind.equals("ACTIVE"))filter+=" AND i.active=1";if(kind.equals("PLANNING_PENDING"))filter+=" AND i.pending=1";
        if(kind.endsWith("ATTENTION"))filter+=(plan?" AND i.uncertain=1":" AND i.state IN ('FAILED','INTERRUPTED')")+" AND EXISTS(SELECT 1 FROM mineagent_runtime_records t WHERE t.world_id=i.world AND t.namespace='tasks' AND t.record_id=i.task AND t.deleted=0 AND json_extract(t.payload,'$.status')='RUNNING' AND (CASE WHEN COALESCE(json_extract(t.payload,'$.intentRevision'),0)<1 THEN MAX(1,json_extract(t.payload,'$.revision')) ELSE json_extract(t.payload,'$.intentRevision') END)=i.intent)";
        var result=new ArrayList<RuntimeRecord>();try(var query=db.prepareStatement("SELECT r.record_id,r.revision,r.payload,r.updated_at FROM "+table+" i JOIN mineagent_runtime_records r ON r.world_id=i.world AND r.record_id=i.id AND r.namespace=? WHERE i.world=? AND r.deleted=0"+filter+" ORDER BY "+(plan?"i.id":"i.task,i.intent,i.round")+" LIMIT ? OFFSET ?")){int index=1;query.setString(index++,ns);query.setString(index++,world.toString());if(task!=null)query.setString(index++,task.toString());if(intent!=null)query.setLong(index++,intent);if(owner!=null)query.setString(index++,owner.toString());query.setInt(index++,limit);query.setInt(index,offset);try(var rows=query.executeQuery()){while(rows.next())result.add(new RuntimeRecord(world,ns,rows.getString(1),rows.getLong(2),rows.getString(3),rows.getLong(4),false));}}return List.copyOf(result);
    }
    static Optional<UUID> generation(Connection db,UUID world,UUID operation)throws SQLException{try(var query=db.prepareStatement("SELECT batch FROM mineagent_world_generation_index_v1 WHERE world=? AND operation=?")){query.setString(1,world.toString());query.setString(2,operation.toString());try(var rows=query.executeQuery()){return rows.next()?Optional.of(UUID.fromString(rows.getString(1))):Optional.empty();}}}
}
