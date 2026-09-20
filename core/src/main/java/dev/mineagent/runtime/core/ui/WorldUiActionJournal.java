package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** Persist intent before calling package code. Unknown Native effects are never replayed on retry/restart. */
public final class WorldUiActionJournal implements AutoCloseable {
    private static final String NS="world_ui_actions_v1";
    public record Input(UUID operationId,UUID viewer,UUID instance,UUID entity,String part,String entry,String canonical,long expectedRevision,String action,String payload,UUID actorId,dev.mineagent.runtime.api.ui.UiProtocol.ActorKind actorKind,UUID taskId,long taskRevision){
        public Input(UUID operationId,UUID viewer,UUID instance,UUID entity,String part,String entry,String canonical,long expectedRevision,String action,String payload){this(operationId,viewer,instance,entity,part,entry,canonical,expectedRevision,action,payload,viewer,dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.PLAYER,null,0);}
        public Input{Objects.requireNonNull(operationId);Objects.requireNonNull(viewer);Objects.requireNonNull(instance);Objects.requireNonNull(entity);
            if(part==null||!part.matches("[A-Za-z0-9_.-]{1,64}")||entry==null||entry.length()>256||canonical==null||!canonical.matches("[a-f0-9]{64}")||expectedRevision<1||action==null||!action.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("WORLD_UI_ACTION_INPUT");payload=WorldUiData.normalize(payload);
            if(actorId==null&&actorKind==null&&taskId==null&&taskRevision==0){actorId=viewer;actorKind=dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.PLAYER;}
            if(actorId==null||actorKind==null||(actorKind==dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.PLAYER?(!actorId.equals(viewer)||taskId!=null||taskRevision!=0):(actorId.equals(viewer)||taskId==null||taskRevision<1)))throw new IllegalArgumentException("WORLD_UI_ACTION_ACTOR");}
    }
    public record Record(Input input,String state,long afterRevision,String response,String error,long revision){}
    public record Prepared(Record record,boolean execute){}
    private final SqliteRuntimeRepository repository;private final UUID world;private final ObjectMapper json=new ObjectMapper();private final Map<UUID,Record> records=new LinkedHashMap<>();private boolean closed;
    private WorldUiActionJournal(SqliteRuntimeRepository repository,UUID world)throws Exception{this.repository=repository;this.world=world;
        for(var row:repository.list(world,NS)){var r=json.readValue(row.payload(),Record.class);if(r.revision()!=row.revision()||!row.recordId().equals(r.input().operationId().toString()))throw new IllegalStateException("WORLD_UI_ACTION_RECORD");records.put(r.input().operationId(),r);}
        for(var r:List.copyOf(records.values()))if(r.state().equals("PREPARING"))save(r.input(),"INTERRUPTED",0,"","SERVER_RESTARTED_NO_REPLAY",r.revision());
    }
    public static WorldUiActionJournal open(Path db,UUID world)throws Exception{var repo=new SqliteRuntimeRepository(db);try{return new WorldUiActionJournal(repo,world);}catch(Exception e){repo.close();throw e;}}
    public synchronized Prepared prepare(Input input,long currentRevision,boolean authorized)throws Exception{
        requireOpen();if(!authorized)throw new SecurityException("WORLD_UI_AUTHORITY_REVOKED");var old=records.get(input.operationId());
        if(old!=null){if(!old.input().equals(input))throw new IllegalArgumentException("OPERATION_ID_REUSED");return new Prepared(old,false);}
        if(input.expectedRevision()!=currentRevision)throw new IllegalStateException("WORLD_UI_STATE_CONFLICT");if(records.size()>=4096)throw new IllegalStateException("WORLD_UI_LEDGER_FULL");
        return new Prepared(save(input,"PREPARING",0,"","",0),true);
    }
    public synchronized Record complete(Input input,long afterRevision,String response)throws Exception{requireOpen();var old=current(input);if(!old.state().equals("PREPARING")||afterRevision<input.expectedRevision())throw new IllegalStateException("WORLD_UI_ACTION_OUTCOME");return save(input,"APPLIED",afterRevision,WorldUiData.normalize(response),"",old.revision());}
    public synchronized Record unknown(Input input,String error)throws Exception{requireOpen();var old=current(input);if(!old.state().equals("PREPARING"))return old;return save(input,"UNKNOWN",0,"",error!=null&&error.matches("[A-Z][A-Z0-9_]{0,63}")?error:"WORLD_UI_HANDLER_FAILED",old.revision());}
    private Record current(Input input){var old=records.get(input.operationId());if(old==null||!old.input().equals(input))throw new IllegalArgumentException("WORLD_UI_ACTION_TICKET");return old;}
    private Record save(Input input,String state,long after,String response,String error,long revision)throws Exception{var next=new Record(input,state,after,response,error,revision+1);if(!repository.compareAndSet(world,NS,input.operationId().toString(),revision,json.writeValueAsString(next),System.currentTimeMillis()).accepted())throw new IllegalStateException("WORLD_UI_ACTION_CAS");records.put(input.operationId(),next);return next;}
    private void requireOpen(){if(closed)throw new IllegalStateException("WORLD_UI_ACTIONS_CLOSED");}
    @Override public synchronized void close()throws Exception{if(!closed){closed=true;repository.close();}}
}
