package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
/** Durable local UI intent. A receipt is historical evidence, never a command to reapply on a new document. */
public final class UiPlacementJournal implements AutoCloseable {
    private static final String NS="ui_placement_ops_v1";
    public record Scope(String server,UUID world,UUID viewer,UUID actor,UUID packageId,String entry,String target){public Scope{Objects.requireNonNull(world);Objects.requireNonNull(viewer);Objects.requireNonNull(actor);Objects.requireNonNull(packageId);if(server==null||server.isBlank()||server.length()>512||entry==null||!entry.startsWith("ui/")||entry.contains("..")||entry.length()>256||target==null||target.length()>512)throw new IllegalArgumentException("UI_PRESENTATION_SCOPE");}}
    public record Input(UUID operationId,Scope scope,long packageRevision,UUID taskId,long taskRevision,long expectedLayoutRevision,UiPresentationAction.Placement placement){public Input{Objects.requireNonNull(operationId);Objects.requireNonNull(scope);Objects.requireNonNull(taskId);Objects.requireNonNull(placement);if(packageRevision<1||taskRevision<1||expectedLayoutRevision<1)throw new IllegalArgumentException("UI_PRESENTATION_INPUT");}}
    public record Record(Input input,String state,String response,String error,long revision){}
    public record Prepared(Record record,boolean execute){}
    private final SqliteRuntimeRepository repo;private final UUID world;private final FileChannel channel;private final FileLock lock;private final ObjectMapper json=new ObjectMapper();private final Map<UUID,Record> records=new LinkedHashMap<>();private boolean closed;
    private UiPlacementJournal(SqliteRuntimeRepository repo,UUID world,FileChannel channel,FileLock lock)throws Exception{this.repo=repo;this.world=world;this.channel=channel;this.lock=lock;
        for(var row:repo.list(world,NS)){var r=json.readValue(row.payload(),Record.class);if(row.revision()!=r.revision()||!r.input().scope().world().equals(world))throw new IllegalStateException("UI_PRESENTATION_RECORD");records.put(r.input().operationId(),r);}if(records.size()>4096)throw new IllegalStateException("UI_PRESENTATION_LEDGER_FULL");
        for(var r:List.copyOf(records.values()))if(r.state().equals("PREPARING"))save(r.input(),"INTERRUPTED","","CLIENT_RESTARTED",r.revision());
    }
    public static UiPlacementJournal open(Path path,UUID world)throws Exception{Path p=path.toAbsolutePath().normalize();Files.createDirectories(p.getParent());FileChannel channel=FileChannel.open(p.resolveSibling(p.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;SqliteRuntimeRepository repo=null;
        try{lock=channel.tryLock();if(lock==null)throw new IllegalStateException("UI_PRESENTATION_JOURNAL_IN_USE");repo=new SqliteRuntimeRepository(p);return new UiPlacementJournal(repo,world,channel,lock);}catch(Exception e){if(repo!=null)repo.close();if(lock!=null)lock.release();channel.close();throw e;}
    }
    public synchronized Prepared prepare(Input input,long currentRevision,boolean authorized)throws Exception{
        if(closed)throw new IllegalStateException("UI_PRESENTATION_JOURNAL_CLOSED");if(!authorized||!world.equals(input.scope().world()))throw new SecurityException("UI_PRESENTATION_DENIED");var old=records.get(input.operationId());
        if(old!=null){if(!old.input().equals(input))throw new IllegalArgumentException("OPERATION_ID_REUSED");return new Prepared(old,false);}if(input.expectedLayoutRevision()!=currentRevision)throw new IllegalStateException("STALE_LAYOUT");if(records.size()>=4096)throw new IllegalStateException("UI_PRESENTATION_LEDGER_FULL");return new Prepared(save(input,"PREPARING","","",0),true);
    }
    public synchronized Record applied(Input input,String response)throws Exception{var old=current(input);if(!old.state().equals("PREPARING")||response==null||response.length()>8192)throw new IllegalStateException("UI_PRESENTATION_OUTCOME");return save(input,"APPLIED",response,"",old.revision());}
    public synchronized Record rejected(Input input,String response)throws Exception{var old=current(input);if(!old.state().equals("PREPARING")||response==null||response.length()>8192)throw new IllegalStateException("UI_PRESENTATION_OUTCOME");return save(input,"REJECTED",response,"",old.revision());}
    public synchronized Record unknown(Input input,String code)throws Exception{var old=current(input);return old.state().equals("PREPARING")?save(input,"UNKNOWN","",code!=null&&code.matches("[A-Z_]{1,80}")?code:"UI_PRESENTATION_UNKNOWN",old.revision()):old;}
    private Record current(Input input){if(closed)throw new IllegalStateException("UI_PRESENTATION_JOURNAL_CLOSED");var old=records.get(input.operationId());if(old==null||!old.input().equals(input))throw new IllegalArgumentException("UI_PRESENTATION_TICKET");return old;}
    private Record save(Input input,String state,String response,String error,long rev)throws Exception{var r=new Record(input,state,response,error,rev+1);if(!repo.compareAndSet(world,NS,input.operationId().toString(),rev,json.writeValueAsString(r),System.currentTimeMillis()).accepted())throw new IllegalStateException("UI_PRESENTATION_CAS");records.put(input.operationId(),r);return r;}
    @Override public synchronized void close()throws Exception{if(closed)return;closed=true;try{repo.close();}finally{try{lock.release();}finally{channel.close();}}}
}
