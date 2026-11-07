package dev.mineagent.runtime.core.persistence;

import java.sql.*;
import java.util.*;

/** Indexed hot scheduling and owner-scoped cold history over the original durable records. */
final class CooperationRetention {
    static final String SPACE="cooperation_v2";
    private CooperationRetention(){}
    static void initialize(Connection db)throws SQLException{
        try(var statement=db.createStatement()){
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_cooperation_active_v2 ON mineagent_runtime_records(world_id,record_id) WHERE namespace='cooperation_v2' AND deleted=0 AND json_extract(payload,'$.state') NOT IN ('COMPLETED','CANCELLED')");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_cooperation_history_v2 ON mineagent_runtime_records(world_id,json_extract(payload,'$.owner'),json_extract(payload,'$.leader'),updated_at DESC,record_id) WHERE namespace='cooperation_v2' AND deleted=0");
        }
    }
    static List<RuntimeRecord> active(Connection db,UUID world)throws SQLException{
        return read(db,world," AND json_extract(payload,'$.state') NOT IN ('COMPLETED','CANCELLED') ORDER BY record_id",List.of());
    }
    static List<RuntimeRecord> history(Connection db,UUID world,UUID owner,UUID leader,int offset,int limit)throws SQLException{
        Objects.requireNonNull(owner);Objects.requireNonNull(leader);if(offset<0||limit<1||limit>33)throw new IllegalArgumentException("COOP_HISTORY_PAGE");
        return read(db,world," AND json_extract(payload,'$.owner')=? AND json_extract(payload,'$.leader')=? ORDER BY updated_at DESC,record_id LIMIT ? OFFSET ?",List.of(owner.toString(),leader.toString(),limit,offset));
    }
    private static List<RuntimeRecord> read(Connection db,UUID world,String filter,List<?> parameters)throws SQLException{
        Objects.requireNonNull(world);var values=new ArrayList<RuntimeRecord>();
        try(var query=db.prepareStatement("SELECT record_id,revision,payload,updated_at FROM mineagent_runtime_records WHERE world_id=? AND namespace='cooperation_v2' AND deleted=0"+filter)){
            query.setString(1,world.toString());for(int i=0;i<parameters.size();i++)query.setObject(i+2,parameters.get(i));
            try(var rows=query.executeQuery()){while(rows.next())values.add(new RuntimeRecord(world,SPACE,rows.getString(1),rows.getLong(2),rows.getString(3),rows.getLong(4),false));}
        }return List.copyOf(values);
    }
}
