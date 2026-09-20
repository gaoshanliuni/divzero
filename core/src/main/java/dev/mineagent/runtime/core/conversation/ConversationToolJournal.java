package dev.mineagent.runtime.core.conversation;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
/** Write-ahead tool intent and durable outcome; uncertain actions are never re-dispatched. Call off the server tick. */
public final class ConversationToolJournal {
    private ConversationToolJournal(){}
    public static void save(Path dbFile,UUID world,UUID id,long expected,String payload)throws SQLException{
        if(payload==null||payload.length()>65536)throw new IllegalArgumentException("AGENT_RECEIPT_BUDGET");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+dbFile.toAbsolutePath());var s=db.createStatement()){
            s.execute("PRAGMA busy_timeout=1500");s.execute("BEGIN IMMEDIATE");
            try{int changed;if(expected==0){try(var q=db.prepareStatement("INSERT INTO mineagent_runtime_records(world_id,namespace,record_id,revision,payload,updated_at,deleted) VALUES(?,'conversation_agent_tools_v1',?,1,?,?,0) ON CONFLICT(world_id,namespace,record_id) DO NOTHING")){q.setString(1,world.toString());q.setString(2,id.toString());q.setString(3,payload);q.setLong(4,System.currentTimeMillis());changed=q.executeUpdate();}}
                else{try(var q=db.prepareStatement("UPDATE mineagent_runtime_records SET revision=?,payload=?,updated_at=? WHERE world_id=? AND namespace='conversation_agent_tools_v1' AND record_id=? AND revision=? AND deleted=0")){q.setLong(1,expected+1);q.setString(2,payload);q.setLong(3,System.currentTimeMillis());q.setString(4,world.toString());q.setString(5,id.toString());q.setLong(6,expected);changed=q.executeUpdate();}}
                if(changed!=1)throw new IllegalStateException("AGENT_TOOL_NOT_REPLAYABLE");s.execute("COMMIT");
            }catch(SQLException|RuntimeException e){try{s.execute("ROLLBACK");}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;}
        }
    }
}
