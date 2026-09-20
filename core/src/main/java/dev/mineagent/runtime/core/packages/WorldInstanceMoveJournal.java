package dev.mineagent.runtime.core.packages;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeInstanceLocation;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.util.*;

/** Explicit instance-scoped translation intent. Unknown world effects are never replayed. */
public final class WorldInstanceMoveJournal implements AutoCloseable {
    private static final String NS="world_instance_moves_v1";
    public record Input(UUID operationId,UUID owner,UUID activation,UUID instance,String canonical,long activationRevision,
                        RuntimeInstanceLocation source,RuntimeInstanceLocation target){
        public Input{Objects.requireNonNull(operationId);Objects.requireNonNull(owner);Objects.requireNonNull(activation);Objects.requireNonNull(instance);Objects.requireNonNull(source);Objects.requireNonNull(target);
            if(canonical==null||!canonical.matches("[a-f0-9]{64}")||activationRevision<1||!source.dimension().equals(target.dimension())||source.yaw()!=target.yaw()||source.pitch()!=target.pitch())throw new IllegalArgumentException("INSTANCE_MOVE_CONTEXT");
            double distance=Math.max(Math.abs(target.x()-source.x()),Math.max(Math.abs(target.y()-source.y()),Math.abs(target.z()-source.z())));
            if(distance==0||distance>64)throw new IllegalArgumentException("INSTANCE_MOVE_RANGE");}
    }
    public record Record(Input input,String state,long beforeRevision,long afterRevision,String error,long revision){}
    public record Prepared(Record record,boolean execute){}
    private final SqliteRuntimeRepository repo;private final UUID world;private final ObjectMapper json=new ObjectMapper();private final Map<UUID,Record> records=new LinkedHashMap<>();private boolean closed;
    private WorldInstanceMoveJournal(Path db,UUID world)throws Exception{this.world=Objects.requireNonNull(world);repo=new SqliteRuntimeRepository(db);
        try{for(var row:repo.list(world,NS)){var r=json.readValue(row.payload(),Record.class);if(r.revision()!=row.revision()||!row.recordId().equals(r.input().operationId().toString())||!Set.of("PREPARING","APPLIED","UNKNOWN","INTERRUPTED").contains(r.state()))throw new IllegalStateException("INSTANCE_MOVE_RECORD");records.put(r.input().operationId(),r);}
            for(var r:List.copyOf(records.values()))if(r.state().equals("PREPARING"))save(r.input(),"INTERRUPTED",r.beforeRevision(),0,"SERVER_RESTARTED_NO_REPLAY",r.revision());
        }catch(Exception e){repo.close();throw e;}}
    public static WorldInstanceMoveJournal open(Path db,UUID world)throws Exception{return new WorldInstanceMoveJournal(db,world);}
    public synchronized Optional<Record> replay(Input input,boolean authorized){requireOpen();if(!authorized)throw new SecurityException("INSTANCE_MOVE_PERMISSION");var r=records.get(input.operationId());if(r!=null&&!r.input().equals(input))throw new IllegalArgumentException("OPERATION_ID_REUSED");return Optional.ofNullable(r);}
    public synchronized Prepared prepare(Input input,long instanceRevision,boolean authorized)throws Exception{
        var old=replay(input,authorized);if(old.isPresent())return new Prepared(old.get(),false);
        if(instanceRevision<1)throw new IllegalArgumentException("INSTANCE_MOVE_REVISION");if(records.size()>=4096)throw new IllegalStateException("INSTANCE_MOVE_LEDGER_FULL");
        if(unresolved().stream().anyMatch(r->r.input().instance().equals(input.instance())))throw new IllegalStateException("INSTANCE_MOVE_RECOVERY_REQUIRED");
        return new Prepared(save(input,"PREPARING",instanceRevision,0,"",0),true);
    }
    public synchronized Record complete(Input input,long afterRevision)throws Exception{var old=current(input);if(!old.state().equals("PREPARING")||afterRevision<=old.beforeRevision())throw new IllegalStateException("INSTANCE_MOVE_OUTCOME");return save(input,"APPLIED",old.beforeRevision(),afterRevision,"",old.revision());}
    public synchronized Record unknown(Input input,String error)throws Exception{var old=current(input);if(!old.state().equals("PREPARING"))return old;return save(input,"UNKNOWN",old.beforeRevision(),0,error!=null&&error.matches("[A-Z][A-Z0-9_]{0,63}")?error:"INSTANCE_MOVE_UNKNOWN",old.revision());}
    public synchronized List<Record> unresolved(){requireOpen();return records.values().stream().filter(r->!r.state().equals("APPLIED")).toList();}
    public synchronized RuntimeInstanceLocation location(UUID instance,String canonical,RuntimeInstanceLocation original){requireOpen();return records.values().stream().filter(r->r.state().equals("APPLIED")&&r.input().instance().equals(instance)&&r.input().canonical().equals(canonical)).max(Comparator.comparingLong(Record::afterRevision)).map(r->r.input().target()).orElse(original);}
    private Record current(Input input){requireOpen();var r=records.get(input.operationId());if(r==null||!r.input().equals(input))throw new IllegalArgumentException("INSTANCE_MOVE_TICKET");return r;}
    private Record save(Input input,String state,long before,long after,String error,long revision)throws Exception{var next=new Record(input,state,before,after,error,revision+1);if(!repo.compareAndSet(world,NS,input.operationId().toString(),revision,json.writeValueAsString(next),System.currentTimeMillis()).accepted())throw new IllegalStateException("INSTANCE_MOVE_CAS");records.put(input.operationId(),next);return next;}
    private void requireOpen(){if(closed)throw new IllegalStateException("INSTANCE_MOVES_CLOSED");}
    @Override public synchronized void close()throws Exception{if(!closed){closed=true;repo.close();}}
}
