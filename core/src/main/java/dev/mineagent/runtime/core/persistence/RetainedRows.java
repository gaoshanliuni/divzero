package dev.mineagent.runtime.core.persistence;

import java.sql.*;
import java.util.*;

/** Logical hot/archive separation. Payloads and primary keys never leave the original durable table. */
public final class RetainedRows {
    public static final long MAX_RETAINED_ROWS=131072;
    private RetainedRows(){}
    public record Usage(long hot,long archived,long total,long payloadBytes,long maximumRetained){}
    private static String table(String name){if(name==null||!name.matches("mineagent_[a-z0-9_]+_v1"))throw new IllegalArgumentException("RETENTION_TABLE");return name;}
    /** Caller holds the store's BEGIN IMMEDIATE during schema initialization. */
    public static void initialize(Connection db,UUID world,List<String> tables)throws Exception{
        try(var statement=db.createStatement()){statement.execute("CREATE TABLE IF NOT EXISTS mineagent_retention_usage_v1(world TEXT NOT NULL,table_name TEXT NOT NULL,row_count INTEGER NOT NULL,payload_bytes INTEGER NOT NULL,PRIMARY KEY(world,table_name))");}
        for(String name:tables){table(name);boolean present=false;try(var query=db.createStatement();var rows=query.executeQuery("PRAGMA table_info("+name+")")){while(rows.next())if(rows.getString("name").equals("archived"))present=true;}
            try(var statement=db.createStatement()){
                if(!present)statement.execute("ALTER TABLE "+name+" ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
                statement.execute("CREATE INDEX IF NOT EXISTS "+name+"_retention_hot ON "+name+"(world,archived)");
                statement.execute("CREATE TRIGGER IF NOT EXISTS "+name+"_retention_insert AFTER INSERT ON "+name+" BEGIN INSERT INTO mineagent_retention_usage_v1 VALUES(NEW.world,'"+name+"',1,length(CAST(NEW.payload AS BLOB))) ON CONFLICT(world,table_name) DO UPDATE SET row_count=row_count+1,payload_bytes=payload_bytes+length(CAST(NEW.payload AS BLOB)); END");
                statement.execute("CREATE TRIGGER IF NOT EXISTS "+name+"_retention_update AFTER UPDATE OF payload ON "+name+" BEGIN UPDATE mineagent_retention_usage_v1 SET payload_bytes=payload_bytes+length(CAST(NEW.payload AS BLOB))-length(CAST(OLD.payload AS BLOB)) WHERE world=NEW.world AND table_name='"+name+"'; END");
                statement.execute("CREATE TRIGGER IF NOT EXISTS "+name+"_retention_delete AFTER DELETE ON "+name+" BEGIN UPDATE mineagent_retention_usage_v1 SET row_count=row_count-1,payload_bytes=payload_bytes-length(CAST(OLD.payload AS BLOB)) WHERE world=OLD.world AND table_name='"+name+"'; END");
            }
            boolean known;try(var query=db.prepareStatement("SELECT 1 FROM mineagent_retention_usage_v1 WHERE world=? AND table_name=?")){query.setString(1,world.toString());query.setString(2,name);try(var rows=query.executeQuery()){known=rows.next();}}
            if(!known)try(var query=db.prepareStatement("INSERT INTO mineagent_retention_usage_v1 SELECT ?,?,COUNT(*),COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM "+name+" WHERE world=?")){query.setString(1,world.toString());query.setString(2,name);query.setString(3,world.toString());query.executeUpdate();}
        }
    }
    public static Usage usage(Connection db,UUID world,String name)throws Exception{
        table(name);long total,bytes,hot;try(var query=db.prepareStatement("SELECT row_count,payload_bytes FROM mineagent_retention_usage_v1 WHERE world=? AND table_name=?")){query.setString(1,world.toString());query.setString(2,name);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("RETENTION_USAGE_MISSING");total=rows.getLong(1);bytes=rows.getLong(2);}}
        try(var query=db.prepareStatement("SELECT COUNT(*) FROM "+name+" WHERE world=? AND archived=0")){query.setString(1,world.toString());try(var rows=query.executeQuery()){hot=rows.next()?rows.getLong(1):0;}}
        if(total<hot||bytes<0)throw new IllegalStateException("RETENTION_USAGE_INVALID");return new Usage(hot,total-hot,total,bytes,MAX_RETAINED_ROWS);
    }
    public static void requireCapacity(Connection db,UUID world,String name)throws Exception{if(usage(db,world,name).total()>=MAX_RETAINED_ROWS)throw new IllegalStateException("RETENTION_TOTAL_ROW_BUDGET");}
    /** Optional byte-growth limit. Existing settlement metadata may grow by at most 4 KiB, not a disk-size guarantee. */
    public static void requirePayloadCapacity(Connection db,UUID world,String name,UUID id,String payload,long maximum,boolean settlement)throws Exception{
        table(name);long old=0;boolean exists=false;try(var query=db.prepareStatement("SELECT length(CAST(payload AS BLOB)) FROM "+name+" WHERE world=? AND id=?")){query.setString(1,world.toString());query.setString(2,id.toString());try(var rows=query.executeQuery()){if(rows.next()){exists=true;old=rows.getLong(1);}}}
        long delta=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length-old;if(delta>0&&usage(db,world,name).payloadBytes()+delta>maximum&&!(exists&&settlement&&delta<=4096))throw new IllegalStateException("RETENTION_PAYLOAD_BYTE_BUDGET");
    }
    /** Only store-selected immutable/terminal rows may be supplied. This never deletes or reserializes a payload. */
    public static int archive(Connection db,UUID world,String name,List<Long> rows)throws Exception{
        table(name);if(rows.size()>256||new HashSet<>(rows).size()!=rows.size())throw new IllegalArgumentException("RETENTION_BATCH");int count=0;
        try(var update=db.prepareStatement("UPDATE "+name+" SET archived=1 WHERE world=? AND rowid=? AND archived=0")){for(long row:rows){update.setString(1,world.toString());update.setLong(2,row);count+=update.executeUpdate();}}return count;
    }
    public static boolean archived(Connection db,UUID world,String name,String id)throws Exception{table(name);try(var query=db.prepareStatement("SELECT archived FROM "+name+" WHERE world=? AND id=?")){query.setString(1,world.toString());query.setString(2,id);try(var rows=query.executeQuery()){return rows.next()&&rows.getInt(1)!=0;}}}
}
