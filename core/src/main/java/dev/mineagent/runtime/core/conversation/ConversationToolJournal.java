package dev.mineagent.runtime.core.conversation;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
/** Write-ahead tool intent and durable outcome; uncertain actions are never re-dispatched. Call off the server tick. */
public final class ConversationToolJournal {
    private ConversationToolJournal(){}
    public static Map<String,Object> inspect(Path file,UUID world,UUID owner,UUID agent,int offset)throws Exception{
        if(offset<0)throw new IllegalArgumentException("AGENT_OPERATION_OFFSET");var json=new com.fasterxml.jackson.databind.ObjectMapper();var rows=new ArrayList<Object>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());var q=db.prepareStatement("SELECT record_id,revision,payload,updated_at FROM mineagent_runtime_records WHERE world_id=? AND namespace='conversation_agent_tools_v1' AND deleted=0 AND json_extract(payload,'$.owner')=? AND json_extract(payload,'$.agent')=? ORDER BY updated_at DESC,record_id LIMIT 9 OFFSET ?")){
            q.setString(1,world.toString());q.setString(2,owner.toString());q.setString(3,agent.toString());q.setInt(4,offset);try(var r=q.executeQuery()){while(r.next()){var n=json.readTree(r.getString(3));rows.add(Map.of("operation",r.getString(1),"revision",r.getLong(2),"tool",n.path("tool").asText(),"state",n.path("state").asText("RECEIPT_RECORDED"),"receiptStatus",n.path("receipt").path("status").asText(""),"receiptLength",n.path("receipt").toString().length(),"at",r.getLong(4)));}}
        }
        boolean more=rows.size()>8;if(more)rows.removeLast();return Map.of("status","OBSERVED","operations",rows,"nextOffset",more?offset+8:-1,"replayAllowed",false);
    }
    public static Map<String,Object> receipt(Path file,UUID world,UUID owner,UUID agent,UUID operation,int offset)throws Exception{
        if(offset<0)throw new IllegalArgumentException("AGENT_OPERATION_OFFSET");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());var q=db.prepareStatement("SELECT payload FROM mineagent_runtime_records WHERE world_id=? AND namespace='conversation_agent_tools_v1' AND record_id=? AND deleted=0 AND json_extract(payload,'$.owner')=? AND json_extract(payload,'$.agent')=?")){
            q.setString(1,world.toString());q.setString(2,operation.toString());q.setString(3,owner.toString());q.setString(4,agent.toString());
            try(var r=q.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("AGENT_OPERATION_NOT_FOUND");
                var n=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getString(1));String value=n.path("receipt").toString();
                if(offset>value.length()||offset>0&&offset<value.length()&&Character.isLowSurrogate(value.charAt(offset)))throw new IllegalArgumentException("AGENT_OPERATION_OFFSET");
                int end=(int)Math.min(value.length(),(long)offset+4096);if(end<value.length()&&end>offset&&Character.isHighSurrogate(value.charAt(end-1)))end--;
                return Map.of("status","OBSERVED","operation",operation,"state",n.path("state").asText("RECEIPT_RECORDED"),"text",value.substring(offset,end),"offset",offset,"nextOffset",end<value.length()?end:-1,"length",value.length(),"replayAllowed",false);
            }
        }
    }
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
