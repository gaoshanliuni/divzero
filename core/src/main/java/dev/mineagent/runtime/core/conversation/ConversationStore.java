package dev.mineagent.runtime.core.conversation;

import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Callable;
import dev.mineagent.runtime.core.config.ConversationBudget;

/** Complete private conversation data; bounded projections are separate from the persisted original text. */
public final class ConversationStore implements AutoCloseable {
    public record Conversation(UUID conversationId,UUID playerId,UUID agentId,String title,long revision,String state,long ordinal,long messageCount,String activeOperation,long createdAt,long updatedAt){}
    public record Listing(List<Conversation> conversations,long nextBefore){}
    public record Message(UUID messageId,long sequence,String role,String status,long revision,int textLength,String errorCode,long personaRevision,long createdAt,long updatedAt){}
    public record Page(Conversation conversation,List<Message> messages,long nextBefore){}
    public record Chunk(UUID messageId,long revision,int offset,int total,String text){}
    public record Thinking(UUID messageId,long revision,int textLength,boolean active){}
    public record Turn(UUID operationId,UUID conversationId,UUID userMessageId,UUID assistantMessageId,long personaRevision,boolean dispatch){}
    public record VoiceClaim(UUID operationId,UUID messageId,String text,boolean dispatch){}
    public record VoiceSettings(String voice,String rate,String pitch,String volume){
        public VoiceSettings{if(voice==null||!voice.matches("[A-Za-z0-9-]{3,80}")||rate==null||!rate.matches("[+-](?:100|[0-9]{1,2})%")||pitch==null||!pitch.matches("[+-](?:100|[0-9]{1,2})Hz")||volume==null||!volume.matches("[+-](?:100|[0-9]{1,2})%"))throw new IllegalArgumentException("CONVERSATION_VOICE_CONFIG_INVALID");}
    }
    public record VoiceJob(UUID operationId,UUID messageId,UUID contextId,VoiceSettings settings,String state,String sha256,String errorCode,long createdAt,long updatedAt){}
    public record ContextUsage(UUID operationId,UUID assistantMessageId,String requestState,String errorCode,
                               ConversationBudget budget,int estimatedTokens,long omittedThrough,int summaryOutputBudget,
                               String summaryStatus,String summaryId,long summaryRevision,ConversationModelReceipt modelReceipt,String inputSource,String speechOperation){}
    private record Operation(String fingerprint,String kind,UUID conversation,UUID user,UUID assistant,long personaRevision){}
    @FunctionalInterface private interface Row<T>{T read(ResultSet r)throws SQLException;}
    private final Connection db;private final UUID world;private final Clock clock;private final ConversationAuditImporter auditImporter;
    private ConversationStore(Connection db,UUID world,Clock clock)throws SQLException{
        this.db=db;this.world=Objects.requireNonNull(world);this.clock=Objects.requireNonNull(clock);
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversations_v1(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,world_id TEXT NOT NULL,id TEXT NOT NULL,player_id TEXT NOT NULL,agent_id TEXT NOT NULL,title TEXT NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,message_count INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(world_id,id))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_conversations_scope_v1 ON mineagent_conversations_v1(world_id,player_id,agent_id,ordinal)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_messages_v1(world_id TEXT NOT NULL,conversation_id TEXT NOT NULL,id TEXT NOT NULL,sequence INTEGER NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,text_length INTEGER NOT NULL,error_code TEXT NOT NULL,persona_revision INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(world_id,id),UNIQUE(world_id,conversation_id,sequence))");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS mineagent_conversation_active_v1 ON mineagent_conversation_messages_v1(world_id,conversation_id) WHERE role='ASSISTANT' AND status IN ('PENDING','GENERATING')");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_operations_v1(world_id TEXT NOT NULL,id TEXT NOT NULL,fingerprint TEXT NOT NULL,kind TEXT NOT NULL,conversation_id TEXT NOT NULL,user_message_id TEXT,assistant_message_id TEXT,persona_revision INTEGER NOT NULL,PRIMARY KEY(world_id,id))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_conversation_turns_v1 ON mineagent_conversation_operations_v1(world_id,conversation_id,kind,assistant_message_id)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_context_v1(world_id TEXT NOT NULL,operation_id TEXT NOT NULL,config_revision INTEGER NOT NULL,input_budget INTEGER NOT NULL,max_summary_calls INTEGER NOT NULL,summary_input_budget INTEGER NOT NULL,estimated_tokens INTEGER NOT NULL DEFAULT -1,omitted_through INTEGER NOT NULL DEFAULT 0,summary_output_budget INTEGER NOT NULL DEFAULT 0,summary_status TEXT NOT NULL DEFAULT 'PLANNING',summary_id TEXT NOT NULL DEFAULT '',summary_revision INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(world_id,operation_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_native_conversation_v1(world_id TEXT NOT NULL,player_id TEXT NOT NULL,agent_id TEXT NOT NULL,conversation_id TEXT NOT NULL,PRIMARY KEY(world_id,player_id,agent_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_thinking_v1(world_id TEXT NOT NULL,message_id TEXT NOT NULL,text TEXT NOT NULL,text_length INTEGER NOT NULL,revision INTEGER NOT NULL,active INTEGER NOT NULL,PRIMARY KEY(world_id,message_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_conversation_voice_v1(world_id TEXT NOT NULL,operation_id TEXT NOT NULL,player_id TEXT NOT NULL,agent_id TEXT NOT NULL,conversation_id TEXT NOT NULL,message_id TEXT NOT NULL,context_id TEXT NOT NULL,voice TEXT NOT NULL,rate TEXT NOT NULL,pitch TEXT NOT NULL,volume TEXT NOT NULL,state TEXT NOT NULL,sha256 TEXT NOT NULL DEFAULT '',error_code TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(world_id,operation_id))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_conversation_voice_scope_v1 ON mineagent_conversation_voice_v1(world_id,player_id,agent_id,conversation_id,created_at)");
        }
        // Additive migration for the previous budget-only schema; no historical identity is invented.
        transactionSchemaModelReceipt();
        auditImporter=new ConversationAuditImporter(db,world,clock);
        // A new owning runtime never replays an uncertain Provider request from an earlier process.
        execute("UPDATE mineagent_conversation_messages_v1 SET status='INTERRUPTED',error_code='RESTART_INTERRUPTED',revision=revision+1,updated_at=? WHERE world_id=? AND status IN ('PENDING','GENERATING')",clock.millis(),world);
        execute("UPDATE mineagent_conversation_voice_v1 SET state='INTERRUPTED',error_code='RESTART_INTERRUPTED',updated_at=? WHERE world_id=? AND state='REQUESTED'",clock.millis(),world);
    }
    public synchronized ConversationAuditImporter.Candidates auditCandidates(UUID viewer,long before)throws SQLException{return auditImporter.candidates(viewer,before);}
    public synchronized Optional<Conversation> auditIdentity(UUID viewer,UUID agent,UUID source)throws SQLException{
        return query("SELECT * FROM mineagent_conversations_v1 WHERE world_id=? AND id=? AND player_id=? AND agent_id=?",this::conversation,world,source,viewer,agent).stream().findFirst();
    }
    public synchronized ConversationAuditImporter.Preview auditPreview(UUID viewer,UUID agent,UUID source,ConversationAuditImporter.Identity identity)throws Exception{return auditImporter.preview(viewer,agent,source,identity);}
    public synchronized List<ConversationAuditImporter.Job> auditJobs(UUID viewer,UUID agent,long before)throws SQLException{return auditImporter.jobs(viewer,agent,before);}
    public synchronized ConversationAuditImporter.Job auditJob(UUID viewer,UUID agent,UUID id)throws SQLException{return auditImporter.get(viewer,agent,id);}
    public synchronized ConversationAuditImporter.Job auditStart(UUID viewer,UUID agent,UUID source,ConversationAuditImporter.Identity identity,long upper,long count,String identityHash)throws Exception{return auditImporter.start(viewer,agent,source,identity,upper,count,identityHash);}
    public synchronized ConversationAuditImporter.Job auditStep(UUID viewer,UUID agent,UUID id,long cursor)throws Exception{return auditImporter.step(viewer,agent,id,cursor);}
    public synchronized Map<String,Object> auditSource(UUID viewer,UUID agent,UUID conversation,UUID message)throws Exception{
        message(viewer,agent,conversation,message);return auditImporter.source(viewer,agent,conversation,message);
    }
    private void transactionSchemaModelReceipt()throws SQLException{
        try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{
            boolean exists=false;try(var columns=s.executeQuery("PRAGMA table_info(mineagent_conversation_context_v1)")){while(columns.next())if(columns.getString("name").equals("model_receipt"))exists=true;}
            if(!exists)s.execute("ALTER TABLE mineagent_conversation_context_v1 ADD COLUMN model_receipt TEXT NOT NULL DEFAULT ''");
            var names=new HashSet<String>();try(var columns=s.executeQuery("PRAGMA table_info(mineagent_conversation_context_v1)")){while(columns.next())names.add(columns.getString("name"));}
            if(!names.contains("input_source"))s.execute("ALTER TABLE mineagent_conversation_context_v1 ADD COLUMN input_source TEXT NOT NULL DEFAULT ''");
            if(!names.contains("speech_operation"))s.execute("ALTER TABLE mineagent_conversation_context_v1 ADD COLUMN speech_operation TEXT NOT NULL DEFAULT ''");
            s.execute("COMMIT");
        }catch(SQLException failure){try{s.execute("ROLLBACK");}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}}
    }
    public static ConversationStore open(Path path,UUID world,Clock clock)throws Exception{
        Path p=path.toAbsolutePath().normalize();if(p.getParent()!=null)Files.createDirectories(p.getParent());var db=DriverManager.getConnection("jdbc:sqlite:"+p);
        try{return new ConversationStore(db,world,clock);}catch(Exception e){db.close();throw e;}
    }
    private <T>T transaction(Callable<T> action)throws Exception{try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");}try{T result=action.call();try(var s=db.createStatement()){s.execute("COMMIT");}return result;}catch(Exception e){try(var s=db.createStatement()){s.execute("ROLLBACK");}catch(Exception rollback){e.addSuppressed(rollback);}throw e;}}
    private void bind(PreparedStatement q,Object...args)throws SQLException{for(int i=0;i<args.length;i++)q.setObject(i+1,args[i] instanceof UUID?args[i].toString():args[i]);}
    private int execute(String sql,Object...args)throws SQLException{try(var q=db.prepareStatement(sql)){bind(q,args);return q.executeUpdate();}}
    private <T>List<T> query(String sql,Row<T> row,Object...args)throws SQLException{try(var q=db.prepareStatement(sql)){bind(q,args);try(var r=q.executeQuery()){var result=new ArrayList<T>();while(r.next())result.add(row.read(r));return result;}}}
    private static UUID uuid(String s){return s==null?null:UUID.fromString(s);}
    private static void text(String text,int max,boolean blank){if(text==null||text.length()>max||!blank&&text.isBlank())throw new IllegalArgumentException("CONVERSATION_TEXT_LIMIT");}
    private static void limit(int limit){if(limit<1||limit>20)throw new IllegalArgumentException("CONVERSATION_PAGE_LIMIT");}
    private String fingerprint(Object...values)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Arrays.asList(values))));}
    private Operation operation(UUID id,String fingerprint)throws SQLException{var list=query("SELECT * FROM mineagent_conversation_operations_v1 WHERE world_id=? AND id=?",r->new Operation(r.getString("fingerprint"),r.getString("kind"),uuid(r.getString("conversation_id")),uuid(r.getString("user_message_id")),uuid(r.getString("assistant_message_id")),r.getLong("persona_revision")),world,id);if(list.isEmpty())return null;var old=list.getFirst();if(fingerprint!=null&&!old.fingerprint().equals(fingerprint))throw new IllegalArgumentException("OPERATION_ID_REUSED");return old;}
    private void remember(UUID id,String fp,String kind,UUID conversation,UUID user,UUID assistant,long persona)throws SQLException{execute("INSERT INTO mineagent_conversation_operations_v1 VALUES(?,?,?,?,?,?,?,?)",world,id,fp,kind,conversation,user,assistant,persona);}
    private Conversation conversation(ResultSet r)throws SQLException{
        UUID id=uuid(r.getString("id"));var active=query("SELECT o.id FROM mineagent_conversation_operations_v1 o JOIN mineagent_conversation_messages_v1 m ON m.world_id=o.world_id AND m.id=o.assistant_message_id WHERE o.world_id=? AND o.conversation_id=? AND o.kind='send' AND m.status IN ('PENDING','GENERATING')",v->v.getString(1),world,id);
        return new Conversation(id,uuid(r.getString("player_id")),uuid(r.getString("agent_id")),r.getString("title"),r.getLong("revision"),r.getString("state"),r.getLong("ordinal"),r.getLong("message_count"),active.isEmpty()?"":active.getFirst(),r.getLong("created_at"),r.getLong("updated_at"));
    }
    public synchronized Conversation get(UUID viewer,UUID agent,UUID id)throws SQLException{var rows=query("SELECT * FROM mineagent_conversations_v1 WHERE world_id=? AND id=? AND player_id=? AND agent_id=?",this::conversation,world,id,viewer,agent);if(rows.isEmpty())throw new SecurityException("CONVERSATION_NOT_OWNED");return rows.getFirst();}
    public synchronized Conversation nativeConversation(UUID viewer,UUID agent)throws Exception{
        var rows=query("SELECT conversation_id FROM mineagent_native_conversation_v1 WHERE world_id=? AND player_id=? AND agent_id=?",r->uuid(r.getString(1)),world,viewer,agent);
        if(!rows.isEmpty()){var current=get(viewer,agent,rows.getFirst());if(current.state().equals("ACTIVE"))return current;}
        else {UUID legacy=UUID.nameUUIDFromBytes(("native-mention-v1|"+world+"|"+viewer+"|"+agent).getBytes(java.nio.charset.StandardCharsets.UTF_8));var operation=operation(legacy,null);if(operation!=null&&operation.kind().equals("create")){var current=get(viewer,agent,operation.conversation());if(current.state().equals("ACTIVE")){bindNative(viewer,agent,current.conversationId());return current;}}}
        var created=create(viewer,agent,UUID.randomUUID(),"原生 @ 对话");bindNative(viewer,agent,created.conversationId());return created;
    }
    public synchronized void bindNative(UUID viewer,UUID agent,UUID conversation)throws SQLException{var c=get(viewer,agent,conversation);if(!c.state().equals("ACTIVE"))throw new IllegalStateException("CONVERSATION_READ_ONLY");execute("INSERT INTO mineagent_native_conversation_v1 VALUES(?,?,?,?) ON CONFLICT(world_id,player_id,agent_id) DO UPDATE SET conversation_id=excluded.conversation_id",world,viewer,agent,conversation);}
    public synchronized Conversation create(UUID viewer,UUID agent,UUID op,String title)throws Exception{
        Objects.requireNonNull(viewer);Objects.requireNonNull(agent);Objects.requireNonNull(op);text(title,128,false);String fp=fingerprint("create",viewer,agent,title);
        return transaction(()->{var old=operation(op,fp);if(old!=null)return get(viewer,agent,old.conversation());UUID id=UUID.randomUUID();long now=clock.millis();execute("INSERT INTO mineagent_conversations_v1(world_id,id,player_id,agent_id,title,revision,state,message_count,created_at,updated_at) VALUES(?,?,?,?,?,1,'ACTIVE',0,?,?)",world,id,viewer,agent,title,now,now);remember(op,fp,"create",id,null,null,0);return get(viewer,agent,id);});
    }
    public synchronized Listing list(UUID viewer,UUID agent,String state,String search,long before,int count)throws SQLException{
        limit(count);text(search,256,true);if(before<0||!Set.of("ACTIVE","ARCHIVED","DELETED","ALL").contains(state))throw new IllegalArgumentException("CONVERSATION_LIST_INPUT");
        var rows=query("SELECT * FROM mineagent_conversations_v1 c WHERE world_id=? AND player_id=? AND agent_id=? AND ordinal<? AND (?='ALL' OR state=?) AND (?='' OR instr(lower(title),lower(?))>0 OR EXISTS(SELECT 1 FROM mineagent_conversation_messages_v1 m WHERE m.world_id=c.world_id AND m.conversation_id=c.id AND instr(lower(m.text),lower(?))>0)) ORDER BY ordinal DESC LIMIT ?",this::conversation,world,viewer,agent,before==0?Long.MAX_VALUE:before,state,state,search,search,search,count+1);
        boolean more=rows.size()>count;if(more)rows.removeLast();return new Listing(List.copyOf(rows),more?rows.getLast().ordinal():0);
    }
    public synchronized Conversation change(UUID viewer,UUID agent,UUID id,UUID op,long expected,String action,String title)throws Exception{
        if(expected<1||!Set.of("rename","archive","delete","restore").contains(action))throw new IllegalArgumentException("CONVERSATION_CHANGE_INPUT");if(action.equals("rename"))text(title,128,false);
        String fp=fingerprint(action,viewer,agent,id,expected,title);
        return transaction(()->{var c=get(viewer,agent,id);if(operation(op,fp)!=null)return c;if(c.revision()!=expected)throw new IllegalStateException("STALE_CONVERSATION_REVISION");String next=action.equals("archive")?"ARCHIVED":action.equals("delete")?"DELETED":action.equals("restore")?"ACTIVE":c.state();
            execute("UPDATE mineagent_conversations_v1 SET title=?,state=?,revision=revision+1,updated_at=? WHERE world_id=? AND id=?",action.equals("rename")?title:c.title(),next,clock.millis(),world,id);remember(op,fp,action,id,null,null,0);return get(viewer,agent,id);});
    }
    public synchronized Turn begin(UUID viewer,UUID agent,UUID id,UUID op,long expected,String original,long personaRevision)throws Exception{
        return begin(viewer,agent,id,op,expected,original,personaRevision,null);
    }
    public synchronized Turn begin(UUID viewer,UUID agent,UUID id,UUID op,long expected,String original,long personaRevision,ConversationBudget budget)throws Exception{
        return begin(viewer,agent,id,op,expected,original,personaRevision,budget,null,null);
    }
    public synchronized Turn begin(UUID viewer,UUID agent,UUID id,UUID op,long expected,String original,long personaRevision,ConversationBudget budget,dev.mineagent.runtime.api.interaction.InteractionSource source,UUID speech)throws Exception{
        if(source!=null&&budget==null)throw new IllegalArgumentException("CONVERSATION_INPUT_SOURCE_INVALID");
        text(original,16384,false);if(expected<1||personaRevision<0)throw new IllegalArgumentException("CONVERSATION_SEND_INPUT");String fp=fingerprint("send",viewer,agent,id,expected,original);
        return transaction(()->{var c=get(viewer,agent,id);var old=operation(op,fp);if(old!=null)return new Turn(op,id,old.user(),old.assistant(),old.personaRevision(),false);
            if(!c.state().equals("ACTIVE"))throw new IllegalStateException("CONVERSATION_READ_ONLY");if(c.revision()!=expected)throw new IllegalStateException("STALE_CONVERSATION_REVISION");if(!c.activeOperation().isEmpty())throw new IllegalStateException("CONVERSATION_BUSY");
            UUID user=UUID.randomUUID(),assistant=UUID.randomUUID();long now=clock.millis();message(id,user,c.messageCount()+1,"USER",original,"COMPLETE",0,now);message(id,assistant,c.messageCount()+2,"ASSISTANT","","PENDING",personaRevision,now);
            execute("UPDATE mineagent_conversations_v1 SET message_count=message_count+2,updated_at=? WHERE world_id=? AND id=?",now,world,id);remember(op,fp,"send",id,user,assistant,personaRevision);
            if(budget!=null)execute("INSERT INTO mineagent_conversation_context_v1(world_id,operation_id,config_revision,input_budget,max_summary_calls,summary_input_budget) VALUES(?,?,?,?,?,?)",world,op,budget.configRevision(),budget.contextTokenBudget(),budget.summaryMaxCalls(),budget.summaryInputBudget());
            if(source!=null)recordInputSource(op,source,speech);
            return new Turn(op,id,user,assistant,personaRevision,true);});
    }

    /** Record only bounded plan metadata. The immutable budget was stored with the original send. */
    public synchronized void recordContext(UUID operation,ConversationContext.Plan plan)throws Exception{
        transaction(()->{
            if(!pending(operation))return null;
            int changed=execute("UPDATE mineagent_conversation_context_v1 SET estimated_tokens=?,omitted_through=?,summary_output_budget=?,summary_status=?,summary_id=?,summary_revision=? WHERE world_id=? AND operation_id=? AND input_budget>=?",
                    plan.estimatedTokens(),plan.omittedThrough(),plan.summaryBudget(),plan.summaryStatus(),plan.summaryId(),plan.summaryRevision(),world,operation,plan.estimatedTokens());
            if(changed!=1)throw new IllegalStateException("CONVERSATION_CONTEXT_POLICY_MISSING");return null;
        });
    }
    public synchronized void recordInputSource(UUID operation,dev.mineagent.runtime.api.interaction.InteractionSource source,UUID speech)throws SQLException{
        if((source==dev.mineagent.runtime.api.interaction.InteractionSource.VOICE)!=(speech!=null))throw new IllegalArgumentException("CONVERSATION_INPUT_SOURCE_INVALID");
        if(execute("UPDATE mineagent_conversation_context_v1 SET input_source=?,speech_operation=? WHERE world_id=? AND operation_id=? AND input_source=''",source.name(),speech==null?"":speech.toString(),world,operation)!=1)throw new IllegalStateException("CONVERSATION_INPUT_SOURCE_CONFLICT");
    }

    /** Latest turn, or an explicitly selected assistant message, always scoped by its private owner. */
    public synchronized Optional<ContextUsage> context(UUID viewer,UUID agent,UUID conversation,UUID assistant)throws SQLException{
        get(viewer,agent,conversation);
        if(assistant!=null)message(viewer,agent,conversation,assistant);
        var rows=query("SELECT o.id AS operation_id,o.assistant_message_id,m.status,m.error_code,c.config_revision,c.input_budget,c.max_summary_calls,c.summary_input_budget,c.estimated_tokens,c.omitted_through,c.summary_output_budget,c.summary_status,c.summary_id,c.summary_revision,c.model_receipt,c.input_source,c.speech_operation FROM mineagent_conversation_operations_v1 o JOIN mineagent_conversation_messages_v1 m ON m.world_id=o.world_id AND m.id=o.assistant_message_id AND m.conversation_id=o.conversation_id LEFT JOIN mineagent_conversation_context_v1 c ON c.world_id=o.world_id AND c.operation_id=o.id WHERE o.world_id=? AND o.conversation_id=? AND o.kind='send' AND (? IS NULL OR o.assistant_message_id=?) ORDER BY m.sequence DESC LIMIT 1",r->{
            boolean recorded=r.getObject("config_revision")!=null;
            var budget=recorded?new ConversationBudget(r.getLong("config_revision"),r.getInt("input_budget"),r.getInt("max_summary_calls"),r.getInt("summary_input_budget")):null;
            ConversationModelReceipt receipt=null;String storedReceipt=r.getString("model_receipt");
            if(storedReceipt!=null&&!storedReceipt.isEmpty())try{receipt=new com.fasterxml.jackson.databind.ObjectMapper().readValue(storedReceipt,ConversationModelReceipt.class);}catch(Exception invalid){throw new SQLException("MODEL_RECEIPT_INVALID",invalid);}
            return new ContextUsage(uuid(r.getString("operation_id")),uuid(r.getString("assistant_message_id")),r.getString("status"),r.getString("error_code"),budget,recorded?r.getInt("estimated_tokens"):-1,r.getLong("omitted_through"),r.getInt("summary_output_budget"),recorded?r.getString("summary_status"):"LEGACY_UNRECORDED",recorded?r.getString("summary_id"):"",r.getLong("summary_revision"),receipt,Objects.toString(r.getString("input_source"),""),Objects.toString(r.getString("speech_operation"),""));
        },world,conversation,assistant,assistant);
        return rows.stream().findFirst();
    }
    private void message(UUID conversation,UUID id,long sequence,String role,String text,String status,long persona,long now)throws SQLException{execute("INSERT INTO mineagent_conversation_messages_v1 VALUES(?,?,?,?,?,?,?,1,?,'',?,?,?)",world,conversation,id,sequence,role,text,status,text.length(),persona,now,now);}
    /** Called only by the independently authorized Native feedback adapter. No assistant/model turn is reserved. */
    public synchronized Message appendFeedback(UUID viewer,UUID agent,UUID id,UUID feedbackId,String original)throws Exception{
        text(original,16384,false);Objects.requireNonNull(feedbackId);String fp=fingerprint("feedback",viewer,agent,id,original);
        return transaction(()->{var c=get(viewer,agent,id);var old=operation(feedbackId,fp);if(old!=null)return message(viewer,agent,id,old.user());
            if(!c.state().equals("ACTIVE"))throw new IllegalStateException("CONVERSATION_READ_ONLY");
            if(!c.activeOperation().isEmpty())throw new IllegalStateException("CONVERSATION_BUSY");
            UUID message=UUID.randomUUID();long now=clock.millis();message(id,message,c.messageCount()+1,"USER",original,"COMPLETE",0,now);
            execute("UPDATE mineagent_conversations_v1 SET message_count=message_count+1,updated_at=? WHERE world_id=? AND id=?",now,world,id);remember(feedbackId,fp,"feedback",id,message,null,0);return message(viewer,agent,id,message);});
    }
    /** Native feedback consumer already generated this answer; append once without starting another model turn. */
    public synchronized Message appendFeedbackReply(UUID viewer,UUID agent,UUID id,UUID operation,String reply)throws Exception{
        text(reply,8192,false);Objects.requireNonNull(operation);String fp=fingerprint("feedback-reply",viewer,agent,id,reply);
        return transaction(()->{var c=get(viewer,agent,id);var old=operation(operation,fp);if(old!=null)return message(viewer,agent,id,old.assistant());if(!c.state().equals("ACTIVE"))throw new IllegalStateException("CONVERSATION_READ_ONLY");if(!c.activeOperation().isEmpty())throw new IllegalStateException("CONVERSATION_BUSY");UUID message=UUID.randomUUID();long now=clock.millis();message(id,message,c.messageCount()+1,"ASSISTANT",reply,"COMPLETE",0,now);execute("UPDATE mineagent_conversations_v1 SET message_count=message_count+1,updated_at=? WHERE world_id=? AND id=?",now,world,id);remember(operation,fp,"feedback-reply",id,null,message,0);return message(viewer,agent,id,message);});
    }
    public synchronized Message feedbackReply(UUID viewer,UUID agent,UUID id,UUID operation)throws Exception{get(viewer,agent,id);var op=operation(operation,null);if(op==null||!op.kind().equals("feedback-reply")||!op.conversation().equals(id))throw new SecurityException("FEEDBACK_REPLY_NOT_OWNED");return message(viewer,agent,id,op.assistant());}
    private Operation turn(UUID op)throws SQLException{var value=operation(op,null);if(value==null||!value.kind().equals("send"))throw new IllegalArgumentException("CONVERSATION_TURN_UNKNOWN");return value;}
    public synchronized boolean pending(UUID op)throws SQLException{var t=turn(op);return !query("SELECT 1 FROM mineagent_conversation_messages_v1 WHERE world_id=? AND id=? AND status IN ('PENDING','GENERATING')",r->1,world,t.assistant()).isEmpty();}
    public synchronized boolean delta(UUID op,String delta)throws Exception{
        text(delta,131072,true);return transaction(()->{var t=turn(op);var rows=query("SELECT text FROM mineagent_conversation_messages_v1 WHERE world_id=? AND id=? AND status IN ('PENDING','GENERATING')",r->r.getString(1),world,t.assistant());if(rows.isEmpty())return false;String value=rows.getFirst()+delta;text(value,131072,true);execute("UPDATE mineagent_conversation_messages_v1 SET text=?,text_length=?,status='GENERATING',revision=revision+1,updated_at=? WHERE world_id=? AND id=?",value,value.length(),clock.millis(),world,t.assistant());return true;});
    }
    /** Provider thinking is private display data, never assistant text, TTS, or context-summary input. */
    public synchronized boolean thinkingDelta(UUID op,String delta,boolean active)throws Exception{
        text(delta,1_000_000,true);
        return transaction(()->{var t=turn(op);if(!pending(op))return false;
            var old=query("SELECT text FROM mineagent_conversation_thinking_v1 WHERE world_id=? AND message_id=?",r->r.getString(1),world,t.assistant());
            if(old.isEmpty()&&delta.isEmpty())return true;
            String value=(old.isEmpty()?"":old.getFirst())+delta;text(value,1_000_000,true);
            execute("INSERT INTO mineagent_conversation_thinking_v1 VALUES(?,?,?,?,1,?) ON CONFLICT(world_id,message_id) DO UPDATE SET text=excluded.text,text_length=excluded.text_length,active=excluded.active,revision=revision+1",world,t.assistant(),value,value.length(),active?1:0);return true;
        });
    }
    public synchronized Thinking thinking(UUID viewer,UUID agent,UUID conversation,UUID message)throws SQLException{
        var m=message(viewer,agent,conversation,message);
        return query("SELECT revision,text_length,active FROM mineagent_conversation_thinking_v1 WHERE world_id=? AND message_id=?",r->new Thinking(message,r.getLong(1),r.getInt(2),r.getInt(3)==1&&Set.of("PENDING","GENERATING").contains(m.status())),world,message).stream().findFirst().orElse(new Thinking(message,0,0,false));
    }
    public synchronized Map<UUID,Thinking> thinkingPage(UUID viewer,UUID agent,UUID conversation,List<Message> messages)throws SQLException{
        get(viewer,agent,conversation);limit(messages.isEmpty()?1:messages.size());var values=new LinkedHashMap<UUID,Thinking>();
        for(var m:messages)if(m.role().equals("ASSISTANT")){var t=thinking(viewer,agent,conversation,m.messageId());if(t.textLength()>0)values.put(m.messageId(),t);}return Map.copyOf(values);
    }
    public synchronized Chunk thinkingChunk(UUID viewer,UUID agent,UUID conversation,UUID message,long revision,int offset,int size)throws SQLException{
        message(viewer,agent,conversation,message);if(offset<0||size<1||size>4096)throw new IllegalArgumentException("CONVERSATION_CHUNK_INPUT");
        var rows=query("SELECT revision,text FROM mineagent_conversation_thinking_v1 WHERE world_id=? AND message_id=?",r->{String value=r.getString(2);if(revision!=r.getLong(1))throw new IllegalStateException("STALE_MESSAGE_REVISION");if(offset>value.length())throw new IllegalArgumentException("CONVERSATION_CHUNK_OFFSET");return new Chunk(message,revision,offset,value.length(),value.substring(offset,Math.min(value.length(),offset+size)));},world,message);
        if(rows.isEmpty())throw new IllegalArgumentException("CONVERSATION_THINKING_UNAVAILABLE");return rows.getFirst();
    }
    public synchronized boolean finish(UUID op,String status,String text,String error)throws Exception{
        return finish(op,status,text,error,null);
    }
    public synchronized boolean finish(UUID op,String status,String text,String error,ConversationModelReceipt receipt)throws Exception{
        if(status.equals("COMPLETE")&&text==null)throw new IllegalArgumentException("CONVERSATION_EMPTY_COMPLETION");
        if(!Set.of("COMPLETE","FAILED","CANCELLED","INTERRUPTED").contains(status)||error==null||!error.matches("[A-Z0-9_]{0,80}"))throw new IllegalArgumentException("CONVERSATION_FINISH_INPUT");if(text!=null)text(text,131072,!status.equals("COMPLETE"));
        return transaction(()->{var t=turn(op);if(!pending(op))return false;
            if(receipt!=null){if(!status.equals("COMPLETE"))throw new IllegalArgumentException("MODEL_RECEIPT_REQUIRES_COMPLETE");if(execute("UPDATE mineagent_conversation_context_v1 SET model_receipt=? WHERE world_id=? AND operation_id=? AND model_receipt=''",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt),world,op)!=1)throw new IllegalStateException("MODEL_RECEIPT_CONFLICT");}
            if(text==null)return execute("UPDATE mineagent_conversation_messages_v1 SET status=?,error_code=?,revision=revision+1,updated_at=? WHERE world_id=? AND id=? AND status IN ('PENDING','GENERATING')",status,error,clock.millis(),world,t.assistant())==1;
            return execute("UPDATE mineagent_conversation_messages_v1 SET text=?,text_length=?,status=?,error_code=?,revision=revision+1,updated_at=? WHERE world_id=? AND id=? AND status IN ('PENDING','GENERATING')",text,text.length(),status,error,clock.millis(),world,t.assistant())==1;});
    }
    public synchronized Conversation cancel(UUID viewer,UUID agent,UUID id,UUID op,UUID target)throws Exception{
        String fp=fingerprint("cancel",viewer,agent,id,target);return transaction(()->{get(viewer,agent,id);if(operation(op,fp)!=null)return get(viewer,agent,id);var t=turn(target);if(!t.conversation().equals(id))throw new SecurityException("CONVERSATION_NOT_OWNED");
            execute("UPDATE mineagent_conversation_messages_v1 SET status='CANCELLED',error_code='USER_CANCELLED',revision=revision+1,updated_at=? WHERE world_id=? AND id=? AND status IN ('PENDING','GENERATING')",clock.millis(),world,t.assistant());remember(op,fp,"cancel",id,null,null,0);return get(viewer,agent,id);});
    }
    private Message metadata(ResultSet r)throws SQLException{return new Message(uuid(r.getString("id")),r.getLong("sequence"),r.getString("role"),r.getString("status"),r.getLong("revision"),r.getInt("text_length"),r.getString("error_code"),r.getLong("persona_revision"),r.getLong("created_at"),r.getLong("updated_at"));}
    public synchronized List<Message> forward(UUID viewer,UUID agent,UUID id,long fromSequence,int count)throws SQLException{
        get(viewer,agent,id);limit(count);if(fromSequence<1)throw new IllegalArgumentException("CONVERSATION_CURSOR");return List.copyOf(query("SELECT id,sequence,role,status,revision,text_length,error_code,persona_revision,created_at,updated_at FROM mineagent_conversation_messages_v1 WHERE world_id=? AND conversation_id=? AND sequence>=? ORDER BY sequence LIMIT ?",this::metadata,world,id,fromSequence,count));
    }
    public synchronized Message message(UUID viewer,UUID agent,UUID conversation,UUID message)throws SQLException{get(viewer,agent,conversation);var rows=query("SELECT id,sequence,role,status,revision,text_length,error_code,persona_revision,created_at,updated_at FROM mineagent_conversation_messages_v1 WHERE world_id=? AND conversation_id=? AND id=?",this::metadata,world,conversation,message);if(rows.isEmpty())throw new SecurityException("CONVERSATION_MESSAGE_NOT_OWNED");return rows.getFirst();}
    public synchronized VoiceClaim claimVoice(UUID viewer,UUID agent,UUID conversation,UUID message,UUID operation)throws Exception{
        return claimVoice(viewer,agent,conversation,message,operation,null,null);
    }
    public synchronized VoiceClaim claimVoice(UUID viewer,UUID agent,UUID conversation,UUID message,UUID operation,UUID context,VoiceSettings settings)throws Exception{
        if((context==null)!=(settings==null))throw new IllegalArgumentException("CONVERSATION_VOICE_CONTEXT_INVALID");
        String fp=fingerprint("voice",viewer,agent,conversation,message);return transaction(()->{get(viewer,agent,conversation);var old=operation(operation,fp);if(old!=null)return new VoiceClaim(operation,message,"",false);
            var messages=query("SELECT text,status,role FROM mineagent_conversation_messages_v1 WHERE world_id=? AND conversation_id=? AND id=?",r->{if(!r.getString(2).equals("COMPLETE")||!r.getString(3).equals("ASSISTANT"))throw new IllegalStateException("CONVERSATION_VOICE_NOT_COMPLETE");String value=r.getString(1);if(value.isBlank()||value.length()>16384)throw new IllegalStateException("CONVERSATION_VOICE_TEXT_LIMIT");return value;},world,conversation,message);
            if(messages.isEmpty())throw new SecurityException("CONVERSATION_MESSAGE_NOT_OWNED");remember(operation,fp,"voice",conversation,null,message,0);
            if(context!=null)execute("INSERT INTO mineagent_conversation_voice_v1(world_id,operation_id,player_id,agent_id,conversation_id,message_id,context_id,voice,rate,pitch,volume,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'REQUESTED',?,?)",world,operation,viewer,agent,conversation,message,context,settings.voice(),settings.rate(),settings.pitch(),settings.volume(),clock.millis(),clock.millis());
            return new VoiceClaim(operation,message,messages.getFirst(),true);});
    }
    private VoiceJob voiceJob(ResultSet r)throws SQLException{return new VoiceJob(uuid(r.getString("operation_id")),uuid(r.getString("message_id")),uuid(r.getString("context_id")),new VoiceSettings(r.getString("voice"),r.getString("rate"),r.getString("pitch"),r.getString("volume")),r.getString("state"),r.getString("sha256"),r.getString("error_code"),r.getLong("created_at"),r.getLong("updated_at"));}
    public synchronized List<VoiceJob> voiceJobs(UUID viewer,UUID agent,UUID conversation)throws SQLException{
        get(viewer,agent,conversation);return List.copyOf(query("SELECT * FROM mineagent_conversation_voice_v1 WHERE world_id=? AND player_id=? AND agent_id=? AND conversation_id=? ORDER BY created_at DESC,operation_id LIMIT 20",this::voiceJob,world,viewer,agent,conversation));
    }
    public synchronized List<VoiceJob> voiceJobs(UUID viewer,UUID agent,UUID conversation,List<UUID> messages)throws SQLException{
        get(viewer,agent,conversation);if(messages.size()>20)throw new IllegalArgumentException("CONVERSATION_PAGE_LIMIT");var result=new ArrayList<VoiceJob>();
        for(var message:messages)result.addAll(query("SELECT * FROM mineagent_conversation_voice_v1 WHERE world_id=? AND player_id=? AND agent_id=? AND conversation_id=? AND message_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1",this::voiceJob,world,viewer,agent,conversation,message));
        return List.copyOf(result);
    }
    public synchronized Optional<VoiceJob> voiceJob(UUID viewer,UUID agent,UUID conversation,UUID op)throws SQLException{
        get(viewer,agent,conversation);return query("SELECT * FROM mineagent_conversation_voice_v1 WHERE world_id=? AND player_id=? AND agent_id=? AND conversation_id=? AND operation_id=?",this::voiceJob,world,viewer,agent,conversation,op).stream().findFirst();
    }
    public synchronized boolean voiceOutcome(UUID operation,String state,String sha,String error)throws SQLException{
        if(!Set.of("AUDIO_READY","TRANSFER_SENT","FAILED","CANCELLED","INTERRUPTED").contains(state)||sha==null||!sha.matches("(?:[a-f0-9]{64})?")||error==null||!error.matches("[A-Z0-9_]{0,80}")||state.equals("AUDIO_READY")&&sha.isEmpty())throw new IllegalArgumentException("CONVERSATION_VOICE_OUTCOME_INVALID");
        String previous=state.equals("TRANSFER_SENT")?"state='AUDIO_READY'":state.equals("AUDIO_READY")?"state='REQUESTED'":"state IN ('REQUESTED','AUDIO_READY')";
        return execute("UPDATE mineagent_conversation_voice_v1 SET state=?,sha256=CASE WHEN ?='' THEN sha256 ELSE ? END,error_code=?,updated_at=? WHERE world_id=? AND operation_id=? AND "+previous,state,sha,sha,error,clock.millis(),world,operation)==1;
    }
    public synchronized Optional<VoiceJob> cancelVoice(UUID viewer,UUID agent,UUID conversation,UUID operation)throws SQLException{
        var old=voiceJob(viewer,agent,conversation,operation);if(old.isEmpty())throw new SecurityException("CONVERSATION_VOICE_NOT_OWNED");voiceOutcome(operation,"CANCELLED","","USER_CANCELLED");return voiceJob(viewer,agent,conversation,operation);
    }
    public synchronized Page messages(UUID viewer,UUID agent,UUID id,long before,int count)throws SQLException{
        limit(count);if(before<0)throw new IllegalArgumentException("CONVERSATION_CURSOR");var c=get(viewer,agent,id);var list=query("SELECT id,sequence,role,status,revision,text_length,error_code,persona_revision,created_at,updated_at FROM mineagent_conversation_messages_v1 WHERE world_id=? AND conversation_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?",this::metadata,world,id,before==0?Long.MAX_VALUE:before,count);Collections.reverse(list);return new Page(c,List.copyOf(list),!list.isEmpty()&&list.getFirst().sequence()>1?list.getFirst().sequence():0);
    }
    public synchronized Chunk chunk(UUID viewer,UUID agent,UUID conversation,UUID message,long revision,int offset,int size)throws SQLException{
        get(viewer,agent,conversation);if(offset<0||size<1||size>4096)throw new IllegalArgumentException("CONVERSATION_CHUNK_INPUT");var rows=query("SELECT revision,text FROM mineagent_conversation_messages_v1 WHERE world_id=? AND conversation_id=? AND id=?",r->{String text=r.getString(2);if(r.getLong(1)!=revision)throw new IllegalStateException("STALE_MESSAGE_REVISION");if(offset>text.length())throw new IllegalArgumentException("CONVERSATION_CHUNK_OFFSET");return new Chunk(message,revision,offset,text.length(),text.substring(offset,Math.min(text.length(),offset+size)));},world,conversation,message);if(rows.isEmpty())throw new SecurityException("CONVERSATION_MESSAGE_NOT_OWNED");return rows.getFirst();
    }
    @Override public synchronized void close()throws SQLException{db.close();}
}
