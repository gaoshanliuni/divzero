package dev.mineagent.runtime.core.task;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Dedicated background journal write: reserve the writer before CAS, never upgrade a stale WAL read snapshot. */
public final class PlayerControlJournal {
    private PlayerControlJournal(){}
    public static long append(Path database,UUID world,UUID operation,long expected,String payload,long at)throws SQLException {
        Objects.requireNonNull(database);Objects.requireNonNull(world);Objects.requireNonNull(operation);
        if(expected<0||expected==Long.MAX_VALUE||payload==null||payload.length()>65536)throw new IllegalArgumentException("PLAYER_BODY_JOURNAL_INPUT");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath());var stmt=db.createStatement()){
            stmt.execute("PRAGMA busy_timeout=1500");stmt.execute("BEGIN IMMEDIATE");
            try{
                int changed;
                if(expected==0){try(var q=db.prepareStatement("INSERT INTO mineagent_runtime_records(world_id,namespace,record_id,revision,payload,updated_at,deleted) VALUES(?,'player_body_operations_v1',?,1,?,?,0) ON CONFLICT(world_id,namespace,record_id) DO NOTHING")){q.setString(1,world.toString());q.setString(2,operation.toString());q.setString(3,payload);q.setLong(4,at);changed=q.executeUpdate();}}
                else{try(var q=db.prepareStatement("UPDATE mineagent_runtime_records SET revision=?,payload=?,updated_at=? WHERE world_id=? AND namespace='player_body_operations_v1' AND record_id=? AND revision=? AND deleted=0")){q.setLong(1,expected+1);q.setString(2,payload);q.setLong(3,at);q.setString(4,world.toString());q.setString(5,operation.toString());q.setLong(6,expected);changed=q.executeUpdate();}}
                if(changed!=1)throw new IllegalStateException("PLAYER_BODY_JOURNAL_CHANGED");
                stmt.execute("COMMIT");return expected+1;
            }catch(SQLException|RuntimeException failure){try{stmt.execute("ROLLBACK");}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }
    }
}
