package dev.mineagent.runtime.core.persistence;

import dev.mineagent.runtime.api.task.ManagedTask;
import java.sql.*;
import java.util.*;

/** Immutable creation-time attribution. It never grants task authority or reconstructs unknown old ancestry. */
public final class TaskBudgetLineage {
    private TaskBudgetLineage() {}
    public record Parent(UUID taskId,long intent,String relation) {
        public Parent { Objects.requireNonNull(taskId);if(intent<1||!Set.of("GENERATION","PATCH","EVENT","SCHEDULE","REPLAN","REPAIR").contains(relation))throw new IllegalArgumentException("TASK_BUDGET_PARENT_INVALID"); }
        public static Parent of(ManagedTask task,String relation){return new Parent(task.taskId(),task.intentRevision(),relation);}
    }
    public record Entry(String world,String task,String owner,String agent,String root,String parent,long parentIntent,String relation,int depth,boolean known) {}
    private static final String INTENT="CASE WHEN COALESCE(json_extract(payload,'$.intentRevision'),0)>0 THEN json_extract(payload,'$.intentRevision') ELSE MAX(1,json_extract(payload,'$.revision')) END";

    static void initialize(Connection db,UUID world)throws SQLException {
        try(var s=db.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try {
                s.execute("CREATE TABLE IF NOT EXISTS mineagent_task_budget_lineage_v1(world TEXT NOT NULL,task TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,root TEXT NOT NULL,parent TEXT NOT NULL,parent_intent INTEGER NOT NULL,relation TEXT NOT NULL,depth INTEGER NOT NULL,known INTEGER NOT NULL CHECK(known IN (0,1)),PRIMARY KEY(world,task))");
                s.execute("CREATE INDEX IF NOT EXISTS mineagent_task_budget_root_v1 ON mineagent_task_budget_lineage_v1(world,root,owner,task)");
                // Missing ancestry is not proof that an old task was a root. Future descendants inherit unknown.
                try(var q=db.prepareStatement("INSERT OR IGNORE INTO mineagent_task_budget_lineage_v1 SELECT world_id,record_id,json_extract(payload,'$.ownerPlayerId'),json_extract(payload,'$.agentId'),'','',0,'LEGACY_UNRESOLVED',0,0 FROM mineagent_runtime_records WHERE world_id=? AND namespace='tasks' AND deleted=0")) {q.setString(1,world.toString());q.executeUpdate();}
                s.execute("COMMIT");
            } catch(SQLException|RuntimeException failure){try{s.execute("ROLLBACK");}catch(SQLException ignored){}throw failure;}
        }
    }

    /** Called in the same transaction as the initial original Task payload INSERT. */
    static void create(Connection db,UUID world,String task,Parent parent)throws SQLException {
        String owner,agent;
        try(var q=db.prepareStatement("SELECT json_extract(payload,'$.ownerPlayerId'),json_extract(payload,'$.agentId') FROM mineagent_runtime_records WHERE world_id=? AND namespace='tasks' AND record_id=? AND deleted=0")) {
            q.setString(1,world.toString());q.setString(2,task);try(var r=q.executeQuery()){if(!r.next())throw new SQLException("TASK_BUDGET_TASK_MISSING");owner=r.getString(1);agent=r.getString(2);}
        }
        String root=task,relation="USER_ROOT",parentId="";long intent=0;int depth=0;boolean known=true;
        if(parent!=null) {
            Entry source=read(db,world.toString(),parent.taskId().toString());
            if(source==null||!source.owner().equals(owner)||!source.agent().equals(agent)||source.task().equals(task))throw new SQLException("TASK_BUDGET_PARENT_CONTEXT");
            try(var q=db.prepareStatement("SELECT "+INTENT+" FROM mineagent_runtime_records WHERE world_id=? AND namespace='tasks' AND record_id=? AND deleted=0")) {
                q.setString(1,world.toString());q.setString(2,parent.taskId().toString());try(var r=q.executeQuery()){if(!r.next()||r.getLong(1)!=parent.intent())throw new SQLException("TASK_BUDGET_PARENT_CHANGED");}
            }
            if(source.depth()>=64)throw new SQLException("TASK_BUDGET_LINEAGE_DEPTH");
            root=source.root();known=source.known();parentId=source.task();intent=parent.intent();relation=parent.relation();depth=source.depth()+1;
        }
        try(var q=db.prepareStatement("INSERT INTO mineagent_task_budget_lineage_v1 VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            q.setString(1,world.toString());q.setString(2,task);q.setString(3,owner);q.setString(4,agent);q.setString(5,root);q.setString(6,parentId);q.setLong(7,intent);q.setString(8,relation);q.setInt(9,depth);q.setBoolean(10,known);q.executeUpdate();
        }
    }

    public static Entry read(Connection db,String world,String task)throws SQLException {
        try(var q=db.prepareStatement("SELECT owner,agent,root,parent,parent_intent,relation,depth,known FROM mineagent_task_budget_lineage_v1 WHERE world=? AND task=?")) {
            q.setString(1,world);q.setString(2,task);try(var r=q.executeQuery()){return r.next()?new Entry(world,task,r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getInt(7),r.getBoolean(8)):null;}
        }
    }
    /** Resolve only the actual persisted task supplied by Native; never accept a wire root/owner claim. */
    public static Entry resolve(Connection db,String world,String task,String agent,long revision,String revisionKind)throws SQLException {
        Entry entry=read(db,world,task);
        if(entry==null||!entry.agent().equals(agent))throw new IllegalArgumentException("SERVICE_BUDGET_TASK_CONTEXT");
        String version=switch(revisionKind){case "MUTATION"->"revision";case "INTENT"->INTENT;default->throw new IllegalArgumentException("SERVICE_BUDGET_TASK_CONTEXT");};
        try(var q=db.prepareStatement("SELECT json_extract(payload,'$.ownerPlayerId'),json_extract(payload,'$.agentId'),"+version+" FROM mineagent_runtime_records WHERE world_id=? AND namespace='tasks' AND record_id=? AND deleted=0")) {
            q.setString(1,world);q.setString(2,task);try(var r=q.executeQuery()){if(!r.next()||!entry.owner().equals(r.getString(1))||!agent.equals(r.getString(2))||revision!=r.getLong(3))throw new IllegalArgumentException("SERVICE_BUDGET_TASK_CONTEXT");}
        }
        return entry;
    }
}
