package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Local audit recovery only. Called under ConversationStore's monitor and on its same connection. */
public final class ConversationAuditImporter {
    public record Identity(UUID worldId,UUID playerId,UUID agentId,UUID sourceConversationId,long revision,String sourceKind){
        public Identity{Objects.requireNonNull(worldId);Objects.requireNonNull(playerId);Objects.requireNonNull(agentId);Objects.requireNonNull(sourceConversationId);
            if(revision<0||!Set.of("LIVE_SERVER_SESSION","PERSISTED_CONVERSATION").contains(sourceKind))throw new IllegalArgumentException("AUDIT_IDENTITY_INVALID");}
    }
    public record Candidate(String sourceConversationId,long lastPromptSequence){}
    public record Candidates(List<Candidate> candidates,long nextBefore,boolean auditAvailable){}
    public record Preview(UUID sourceConversationId,long upperSequence,long availableRecords,String identityHash,String identityKind,String state){}
    public record Job(UUID jobId,UUID sourceConversationId,UUID conversationId,long upperSequence,long expectedRecords,long cursor,long seenRecords,long importedRecords,String state,String errorCode,long updatedAt){}
    private record Row(long sequence,String actor,String action,String target,String payload,long createdAt){}
    private final Connection db;private final UUID world;private final Clock clock;private final ObjectMapper json=new ObjectMapper();
    ConversationAuditImporter(Connection db,UUID world,Clock clock)throws SQLException{
        this.db=db;this.world=world;this.clock=clock;
        try(var s=db.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_import_jobs_v1(world_id TEXT NOT NULL,id TEXT NOT NULL,player_id TEXT NOT NULL,agent_id TEXT NOT NULL,source_id TEXT NOT NULL,conversation_id TEXT NOT NULL,upper_sequence INTEGER NOT NULL,expected_records INTEGER NOT NULL,cursor INTEGER NOT NULL DEFAULT 0,seen_records INTEGER NOT NULL DEFAULT 0,imported_records INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL,error_code TEXT NOT NULL DEFAULT '',identity_json TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(world_id,id))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_conversation_import_scope_v1 ON mineagent_conversation_import_jobs_v1(world_id,player_id,agent_id,upper_sequence)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_import_sources_v1(world_id TEXT NOT NULL,audit_sequence INTEGER NOT NULL,conversation_id TEXT NOT NULL,message_id TEXT NOT NULL,source_hash TEXT NOT NULL,source_json TEXT NOT NULL,job_id TEXT NOT NULL,PRIMARY KEY(world_id,audit_sequence))");
            if(available()){
                s.execute("CREATE INDEX IF NOT EXISTS mineagent_audit_own_prompts_v1 ON mineagent_runtime_audit(world_id,actor,target,sequence) WHERE action='CONVERSATION_PROMPT'");
                s.execute("CREATE INDEX IF NOT EXISTS mineagent_audit_conversation_rows_v1 ON mineagent_runtime_audit(world_id,target,sequence) WHERE action IN ('CONVERSATION_PROMPT','CONVERSATION_RESPONSE')");
            }
        }
    }
    private void bind(PreparedStatement q,Object... values)throws SQLException{for(int i=0;i<values.length;i++)q.setObject(i+1,values[i] instanceof UUID?values[i].toString():values[i]);}
    private int execute(String sql,Object... args)throws SQLException{try(var q=db.prepareStatement(sql)){bind(q,args);return q.executeUpdate();}}
    private long number(String sql,Object... args)throws SQLException{try(var q=db.prepareStatement(sql)){bind(q,args);try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private boolean available()throws SQLException{return number("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='mineagent_runtime_audit'")>0;}
    private <T>T transaction(java.util.concurrent.Callable<T> action)throws Exception{
        try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{T result=action.call();s.execute("COMMIT");return result;}catch(Exception failed){try{s.execute("ROLLBACK");}catch(SQLException rollback){failed.addSuppressed(rollback);}throw failed;}}
    }
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static UUID stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
    private static String canonical(String value){if(value==null)return "";try{var id=UUID.fromString(value);return id.toString().equalsIgnoreCase(value)?id.toString():"";}catch(IllegalArgumentException invalid){return "";}}
    private boolean archiveEligible(UUID viewer,UUID agent,UUID destination)throws SQLException{
        return number("SELECT COUNT(*) FROM mineagent_conversations_v1 c WHERE c.world_id=? AND c.id=? AND c.player_id=? AND c.agent_id=? AND c.state='ARCHIVED' AND NOT EXISTS(SELECT 1 FROM mineagent_conversation_messages_v1 m WHERE m.world_id=c.world_id AND m.conversation_id=c.id AND m.status<>'IMPORTED_AUDIT')",world,destination,viewer,agent)==1;
    }
    Candidates candidates(UUID viewer,long before)throws SQLException{
        if(before<0)throw new IllegalArgumentException("AUDIT_CURSOR_INVALID");if(!available())return new Candidates(List.of(),0,false);
        var rows=new ArrayList<Candidate>();
        // Discover only targets from this player's own prompt records. No worker/peer text is projected.
        try(var q=db.prepareStatement("SELECT CASE WHEN length(target)=36 THEN target ELSE '' END,MAX(sequence) AS last FROM mineagent_runtime_audit WHERE world_id=? AND actor=? AND action='CONVERSATION_PROMPT' GROUP BY target HAVING MAX(sequence)<? ORDER BY last DESC LIMIT 21")){
            bind(q,world,viewer,before==0?Long.MAX_VALUE:before);try(var r=q.executeQuery()){while(r.next())rows.add(new Candidate(canonical(r.getString(1)),r.getLong(2)));}
        }
        boolean more=rows.size()>20;if(more)rows.removeLast();return new Candidates(List.copyOf(rows),more?rows.getLast().lastPromptSequence():0,true);
    }
    Preview preview(UUID viewer,UUID agent,UUID source,Identity identity)throws Exception{
        if(!available())return new Preview(source,0,0,"","","NO_AUDIT_STORE");
        long own=number("SELECT COUNT(*) FROM mineagent_runtime_audit WHERE world_id=? AND target=? AND action='CONVERSATION_PROMPT' AND actor=?",world,source,viewer);
        if(identity==null)return new Preview(source,0,own,"","","IDENTITY_UNVERIFIED");
        if(!identity.worldId().equals(world)||!identity.playerId().equals(viewer)||!identity.agentId().equals(agent)||!identity.sourceConversationId().equals(source))throw new SecurityException("AUDIT_IDENTITY_SCOPE");
        long foreign=number("SELECT COUNT(*) FROM mineagent_runtime_audit WHERE world_id=? AND target=? AND ((action='CONVERSATION_PROMPT' AND actor<>?) OR (action='CONVERSATION_RESPONSE' AND actor<>'worker'))",world,source,viewer);
        if(foreign>0)return new Preview(source,0,0,"",identity.sourceKind(),"SOURCE_IDENTITY_CONFLICT");
        long upper=number("SELECT COALESCE(MAX(sequence),0) FROM mineagent_runtime_audit WHERE world_id=? AND target=? AND action IN ('CONVERSATION_PROMPT','CONVERSATION_RESPONSE')",world,source);
        long count=number("SELECT COUNT(*) FROM mineagent_runtime_audit WHERE world_id=? AND target=? AND sequence<=? AND action IN ('CONVERSATION_PROMPT','CONVERSATION_RESPONSE')",world,source,upper);
        return new Preview(source,upper,count,hash(json.writeValueAsString(identity)),identity.sourceKind(),count>0?"VERIFIED":"NO_RECORDS");
    }
    private Job job(ResultSet r)throws SQLException{return new Job(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("source_id")),UUID.fromString(r.getString("conversation_id")),r.getLong("upper_sequence"),r.getLong("expected_records"),r.getLong("cursor"),r.getLong("seen_records"),r.getLong("imported_records"),r.getString("state"),r.getString("error_code"),r.getLong("updated_at"));}
    Job get(UUID viewer,UUID agent,UUID id)throws SQLException{
        try(var q=db.prepareStatement("SELECT * FROM mineagent_conversation_import_jobs_v1 WHERE world_id=? AND player_id=? AND agent_id=? AND id=?")){bind(q,world,viewer,agent,id);try(var r=q.executeQuery()){if(!r.next())throw new SecurityException("AUDIT_IMPORT_NOT_OWNED");return job(r);}}
    }
    List<Job> jobs(UUID viewer,UUID agent,long before)throws SQLException{
        if(before<0)throw new IllegalArgumentException("AUDIT_CURSOR_INVALID");var result=new ArrayList<Job>();
        try(var q=db.prepareStatement("SELECT * FROM mineagent_conversation_import_jobs_v1 WHERE world_id=? AND player_id=? AND agent_id=? AND upper_sequence<? ORDER BY upper_sequence DESC,id LIMIT 20")){bind(q,world,viewer,agent,before==0?Long.MAX_VALUE:before);try(var r=q.executeQuery()){while(r.next())result.add(job(r));}}return List.copyOf(result);
    }
    Job start(UUID viewer,UUID agent,UUID source,Identity identity,long expectedUpper,long expectedCount,String expectedIdentityHash)throws Exception{
        return transaction(()->{
            var preview=preview(viewer,agent,source,identity);
            if(!preview.state().equals("VERIFIED"))throw new IllegalStateException("AUDIT_"+preview.state());
            if(preview.upperSequence()!=expectedUpper||preview.availableRecords()!=expectedCount||!preview.identityHash().equals(expectedIdentityHash))throw new IllegalStateException("AUDIT_PREVIEW_CHANGED");
            UUID id=stable("audit-import|"+world+"|"+viewer+"|"+agent+"|"+source+"|"+expectedUpper);
            if(number("SELECT COUNT(*) FROM mineagent_conversation_import_jobs_v1 WHERE world_id=? AND id=?",world,id)>0)return get(viewer,agent,id);
            UUID destination=stable("audit-archive|"+world+"|"+viewer+"|"+agent+"|"+source);
            if(number("SELECT COUNT(*) FROM mineagent_conversations_v1 WHERE world_id=? AND id=?",world,destination)==0){
                execute("INSERT INTO mineagent_conversations_v1(world_id,id,player_id,agent_id,title,revision,state,message_count,created_at,updated_at) VALUES(?,?,?,?,?,1,'ARCHIVED',0,?,?)",world,destination,viewer,agent,"旧审计片段 · "+source.toString().substring(0,8),clock.millis(),clock.millis());
            }else if(!archiveEligible(viewer,agent,destination))throw new IllegalStateException("AUDIT_ARCHIVE_NOT_ARCHIVED");
            execute("INSERT INTO mineagent_conversation_import_jobs_v1(world_id,id,player_id,agent_id,source_id,conversation_id,upper_sequence,expected_records,state,identity_json,updated_at) VALUES(?,?,?,?,?,?,?,?,'READY',?,?)",world,id,viewer,agent,source,destination,expectedUpper,expectedCount,json.writeValueAsString(identity),clock.millis());
            return get(viewer,agent,id);
        });
    }
    private Job stop(UUID viewer,UUID agent,Job job,String state,String error)throws SQLException{
        execute("UPDATE mineagent_conversation_import_jobs_v1 SET state=?,error_code=?,updated_at=? WHERE world_id=? AND id=?",state,error,clock.millis(),world,job.jobId());return get(viewer,agent,job.jobId());
    }
    Job step(UUID viewer,UUID agent,UUID id,long expectedCursor)throws Exception{
        return transaction(()->{
            var job=get(viewer,agent,id);if(!Set.of("READY","PAUSED_ARCHIVE").contains(job.state())||job.cursor()!=expectedCursor)return job;
            if(!archiveEligible(viewer,agent,job.conversationId())){
                boolean continued=number("SELECT COUNT(*) FROM mineagent_conversation_messages_v1 WHERE world_id=? AND conversation_id=? AND status<>'IMPORTED_AUDIT'",world,job.conversationId())>0;
                return stop(viewer,agent,job,continued?"BLOCKED":"PAUSED_ARCHIVE",continued?"AUDIT_ARCHIVE_HAS_NEW_MESSAGES":"AUDIT_ARCHIVE_NOT_ARCHIVED");
            }
            if(job.state().equals("PAUSED_ARCHIVE")){execute("UPDATE mineagent_conversation_import_jobs_v1 SET state='READY',error_code='' WHERE world_id=? AND id=?",world,id);job=get(viewer,agent,id);}
            if(!available())return stop(viewer,agent,job,"INCOMPLETE","AUDIT_SOURCE_LOST");
            var rows=new ArrayList<Row>();
            try(var q=db.prepareStatement("SELECT sequence,actor,action,target,CASE WHEN length(payload)<=131072 THEN payload ELSE NULL END,created_at FROM mineagent_runtime_audit WHERE world_id=? AND target=? AND sequence>? AND sequence<=? AND action IN ('CONVERSATION_PROMPT','CONVERSATION_RESPONSE') ORDER BY sequence LIMIT 8")){
                bind(q,world,job.sourceConversationId(),job.cursor(),job.upperSequence());try(var r=q.executeQuery()){while(r.next())rows.add(new Row(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6)));}
            }
            if(rows.isEmpty())return stop(viewer,agent,job,job.seenRecords()==job.expectedRecords()?"COMPLETE":"INCOMPLETE",job.seenRecords()==job.expectedRecords()?"":"AUDIT_SOURCE_LOST");
            for(var row:rows){if(!(row.action().equals("CONVERSATION_PROMPT")?row.actor().equals(viewer.toString()):row.actor().equals("worker")))return stop(viewer,agent,job,"BLOCKED","AUDIT_SOURCE_IDENTITY_CONFLICT");
                if(row.payload()==null||row.payload().isBlank()||row.payload().length()>131072||row.createdAt()<0)return stop(viewer,agent,job,"BLOCKED","AUDIT_SOURCE_UNSUPPORTED");}
            // Validate the entire batch before writing any message; changed evidence cannot partially commit.
            for(var row:rows){String digest=hash(json.writeValueAsString(Arrays.asList(world,row.sequence(),row.actor(),row.action(),row.target(),row.payload(),row.createdAt())));
                try(var q=db.prepareStatement("SELECT conversation_id,source_hash FROM mineagent_conversation_import_sources_v1 WHERE world_id=? AND audit_sequence=?")){bind(q,world,row.sequence());try(var r=q.executeQuery()){
                    if(r.next()&&(!r.getString(1).equals(job.conversationId().toString())||!r.getString(2).equals(digest)))return stop(viewer,agent,job,"BLOCKED","AUDIT_SOURCE_CHANGED");
                }}
            }
            long next=number("SELECT message_count FROM mineagent_conversations_v1 WHERE world_id=? AND id=?",world,job.conversationId()),added=0;
            for(var row:rows){String sourceJson=json.writeValueAsString(Arrays.asList(world,row.sequence(),row.actor(),row.action(),row.target(),row.payload(),row.createdAt()));String digest=hash(sourceJson);
                try(var q=db.prepareStatement("SELECT conversation_id,source_hash FROM mineagent_conversation_import_sources_v1 WHERE world_id=? AND audit_sequence=?")){bind(q,world,row.sequence());try(var r=q.executeQuery()){if(r.next()){if(!r.getString(1).equals(job.conversationId().toString())||!r.getString(2).equals(digest))throw new IllegalStateException("AUDIT_SOURCE_CHANGED");continue;}}}
                UUID message=stable("audit-message|"+world+"|"+row.sequence());String role=row.action().equals("CONVERSATION_PROMPT")?"USER":"ASSISTANT";
                execute("INSERT INTO mineagent_conversation_messages_v1(world_id,conversation_id,id,sequence,role,text,status,revision,text_length,error_code,persona_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,'IMPORTED_AUDIT',1,?,'AUDIT_TEXT_NOT_ORIGINAL',0,?,?)",world,job.conversationId(),message,++next,role,row.payload(),row.payload().length(),row.createdAt(),row.createdAt());
                execute("INSERT INTO mineagent_conversation_import_sources_v1 VALUES(?,?,?,?,?,?,?)",world,row.sequence(),job.conversationId(),message,digest,sourceJson,id);added++;
            }
            if(added>0)execute("UPDATE mineagent_conversations_v1 SET message_count=?,revision=revision+1,updated_at=? WHERE world_id=? AND id=?",next,clock.millis(),world,job.conversationId());
            execute("UPDATE mineagent_conversation_import_jobs_v1 SET cursor=?,seen_records=seen_records+?,imported_records=imported_records+?,updated_at=? WHERE world_id=? AND id=?",rows.getLast().sequence(),rows.size(),added,clock.millis(),world,id);
            return get(viewer,agent,id);
        });
    }
    Map<String,Object> source(UUID viewer,UUID agent,UUID conversation,UUID message)throws Exception{
        try(var q=db.prepareStatement("SELECT audit_sequence,source_hash,job_id FROM mineagent_conversation_import_sources_v1 WHERE world_id=? AND conversation_id=? AND message_id=?")){
            bind(q,world,conversation,message);try(var r=q.executeQuery()){if(!r.next())return Map.of("state","NOT_IMPORTED");
                var job=get(viewer,agent,UUID.fromString(r.getString(3)));
                return Map.of("state","IMPORTED_AUDIT","sourceConversationId",job.sourceConversationId(),"sourceSequence",r.getLong(1),"sourceHash",r.getString(2),"jobId",job.jobId(),"evidence","PROTECTED_AUDIT_COPY");}
        }
    }
}
