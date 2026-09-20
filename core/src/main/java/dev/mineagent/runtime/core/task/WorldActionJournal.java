package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.persistence.WorldActionRetention;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Durable dispatch/verification ledger, not a transaction over arbitrary native side effects. */
public final class WorldActionJournal implements AutoCloseable {
    public record Receipt(UUID operationId,int index,String tool,boolean verified,String code,Map<String,String> before,Map<String,String> after){public Receipt{before=Map.copyOf(before);after=Map.copyOf(after);}}
    public record Batch(UUID batchId,UUID worldId,UUID taskId,long intent,UUID agentId,UUID owner,String dimension,List<WorldActionSpec> actions,int cursor,String state,String error,Map<String,String> before,List<Receipt> receipts,long revision,int round){public Batch{actions=List.copyOf(actions);before=Map.copyOf(before);receipts=List.copyOf(receipts);}public UUID operationId(){return UUID.nameUUIDFromBytes((batchId+"|"+cursor).getBytes(StandardCharsets.UTF_8));}}
    public record Planning(UUID taskId,long intent,int attempts,boolean pending,boolean uncertain,long revision){}
    private final Map<String,Planning> planning=new LinkedHashMap<>();private final Map<String,Planning> planningCache=new LinkedHashMap<>(128,.75f,true);private final Map<UUID,Batch> historyCache=new LinkedHashMap<>(128,.75f,true);
    private static final String NS="world_action_batches_v1";private final SqliteRuntimeRepository repo;private final UUID world;private final ObjectMapper json=new ObjectMapper();private final Map<UUID,Batch> batches=new LinkedHashMap<>();
    private WorldActionJournal(Path path,UUID world)throws Exception{this.world=world;repo=new SqliteRuntimeRepository(path);try{
        repo.initializeWorldActionRetention(world);for(var r:repo.worldActionRows(world,null,null,null,"ACTIVE",0,(int)WorldActionRetention.MAX_ROWS))cacheBatch(json.readValue(r.payload(),Batch.class));
        for(var b:List.copyOf(batches.values()))if(Set.of("READY","EXECUTING").contains(b.state()))interrupt(b.batchId(),"SERVER_RESTARTED_NO_REPLAY");
        for(var r:repo.worldActionRows(world,null,null,null,"PLANNING_PENDING",0,(int)WorldActionRetention.MAX_ROWS)){var p=json.readValue(r.payload(),Planning.class);planning.put(key(p.taskId(),p.intent()),p);endPlanning(p.taskId(),p.intent(),false);}
    }catch(Exception e){repo.close();throw e;}}
    public static WorldActionJournal open(Path path,UUID world)throws Exception{return new WorldActionJournal(path,Objects.requireNonNull(world));}
    public synchronized Optional<Batch> forTask(UUID task,long intent){return history(task,intent).stream().max(Comparator.comparingInt(Batch::round));}
    public synchronized List<Batch> active(){return List.copyOf(batches.values());}
    public synchronized List<Batch> all(){return readBatches(null,null,null,"ALL",0,(int)WorldActionRetention.MAX_ROWS);}
    public synchronized List<Batch> history(UUID task,long intent){return readBatches(task,intent,null,"ALL",0,16);}
    public synchronized List<Batch> historyPage(UUID owner,UUID task,int offset,int limit){return readBatches(task,null,owner,"ALL",offset,limit);}
    public synchronized List<Batch> attention(){return readBatches(null,null,null,"ATTENTION",0,256);}
    public synchronized List<Batch> ownerHistory(UUID owner){return readBatches(null,null,owner,"ALL",0,(int)WorldActionRetention.MAX_ROWS);}
    private List<Batch> readBatches(UUID task,Long intent,UUID owner,String kind,int offset,int limit){try{var values=new ArrayList<Batch>();for(var row:repo.worldActionRows(world,task,intent,owner,kind,offset,limit))values.add(decode(row));return List.copyOf(values);}catch(Exception unavailable){throw new IllegalStateException("WORLD_ACTION_HISTORY_UNAVAILABLE",unavailable);}}
    /** Exact durable ownership survives cache retirement and the last cursor. */
    public synchronized Optional<Batch> generationBatch(UUID operation){try{return repo.worldGenerationOwner(world,operation).map(this::get);}catch(Exception unavailable){throw new IllegalStateException("WORLD_GENERATION_OWNERSHIP_UNAVAILABLE",unavailable);}}
    public synchronized Batch get(UUID id){var found=batches.get(id);if(found==null)found=historyCache.get(id);if(found!=null)return found;try{var row=repo.get(world,NS,id.toString()).orElseThrow(()->new IllegalStateException("WORLD_ACTION_MISSING"));var b=decode(row);cacheBatch(b);return b;}catch(Exception unavailable){throw new IllegalStateException("WORLD_ACTION_HISTORY_UNAVAILABLE",unavailable);}}
    private Batch decode(dev.mineagent.runtime.core.persistence.RuntimeRecord row)throws Exception{var b=json.readValue(row.payload(),Batch.class);if(!world.equals(b.worldId())||!row.recordId().equals(b.batchId().toString())||row.revision()!=b.revision())throw new IllegalStateException("WORLD_ACTION_RECORD");return b;}
    private void cacheBatch(Batch b){if(Set.of("READY","EXECUTING","VERIFIED").contains(b.state())){historyCache.remove(b.batchId());batches.put(b.batchId(),b);}else{batches.remove(b.batchId());historyCache.put(b.batchId(),b);while(historyCache.size()>128)historyCache.remove(historyCache.keySet().iterator().next());}}
    public synchronized Batch accept(ManagedTask task,String dimension,List<WorldActionSpec> actions)throws Exception{
        return accept(task,dimension,actions,0);
    }
    public synchronized Batch accept(ManagedTask task,String dimension,List<WorldActionSpec> actions,int round)throws Exception{
        if(round<0||round>=16)throw new IllegalArgumentException("WORLD_ACTION_ROUND_LIMIT");
        if(!task.worldId().equals(world)||dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||actions==null||actions.isEmpty()||actions.size()>256||actions.stream().map(WorldActionSpec::callId).distinct().count()!=actions.size())throw new IllegalArgumentException("WORLD_PLAN_CONTEXT");
        var prior=history(task.taskId(),task.intentRevision());var old=prior.stream().filter(b->b.round()==round).findFirst().orElse(null);if(old!=null){if(!old.agentId().equals(task.agentId())||!old.owner().equals(task.ownerPlayerId())||!old.dimension().equals(dimension)||!old.actions().equals(actions))throw new IllegalArgumentException("WORLD_PLAN_REUSED");return old;}
        var latest=forTask(task.taskId(),task.intentRevision()).orElse(null);if(round>0&&(latest==null||latest.round()!=round-1||!latest.state().equals("COMPLETED")))throw new IllegalStateException("PREVIOUS_ROUND_NOT_COMPLETE");
        if(prior.stream().mapToInt(b->b.actions().size()).sum()+actions.size()>256)throw new IllegalStateException("WORLD_ACTION_BUDGET");
        var id=UUID.nameUUIDFromBytes(("world-actions|"+world+"|"+task.taskId()+"|"+task.intentRevision()+(round==0?"":"|"+round)).getBytes(StandardCharsets.UTF_8));
        var b=new Batch(id,world,task.taskId(),task.intentRevision(),task.agentId(),task.ownerPlayerId(),dimension,actions,0,"READY","",Map.of(),List.of(),1,round);store(b,0);return b;
    }
    public synchronized Batch begin(UUID id,Map<String,String> before)throws Exception{var b=get(id);if(!b.state().equals("READY"))throw new IllegalStateException("WORLD_ACTION_NOT_READY");bound(before);return save(b,b.cursor(),"EXECUTING","",before,b.receipts());}
    public synchronized Batch completeAction(UUID id,boolean verified,String code,Map<String,String> after)throws Exception{
        var b=get(id);if(!b.state().equals("EXECUTING"))throw new IllegalStateException("WORLD_ACTION_NOT_EXECUTING");bound(after);code(code);
        var receipts=new ArrayList<>(b.receipts());receipts.add(new Receipt(b.operationId(),b.cursor(),b.actions().get(b.cursor()).tool(),verified,code,b.before(),after));
        int cursor=b.cursor()+(verified?1:0);return save(b,cursor,verified?(cursor==b.actions().size()?"VERIFIED":"READY"):"FAILED",code,Map.of(),receipts);
    }
    public synchronized Batch interrupt(UUID id,String code)throws Exception{var b=get(id);if(!Set.of("READY","EXECUTING","VERIFIED").contains(b.state()))return b;code(code);return save(b,b.cursor(),"INTERRUPTED",code,b.before(),b.receipts());}
    public synchronized Batch completed(UUID id)throws Exception{var b=get(id);if(b.state().equals("COMPLETED"))return b;if(!b.state().equals("VERIFIED"))throw new IllegalStateException("WORLD_ACTIONS_NOT_VERIFIED");return save(b,b.cursor(),"COMPLETED","",Map.of(),b.receipts());}
    public synchronized Batch resumeWorldWait(UUID id)throws Exception{
        var b=get(id);
        if(!resumableWorldWait(b))throw new IllegalStateException("WORLD_WAIT_NOT_RECOVERABLE");
        return save(b,b.cursor(),"READY","",Map.of(),b.receipts());
    }
    public static boolean resumableWorldWait(Batch b){return b!=null&&b.state().equals("INTERRUPTED")&&b.cursor()<b.actions().size()&&Set.of("SERVER_RESTARTED_NO_REPLAY","TASK_CHANGED").contains(b.error())
            &&b.actions().subList(b.cursor(),b.actions().size()).stream().allMatch(a->Set.of("await_world_activation","inspect_world_content").contains(a.tool()));}
    private Batch save(Batch b,int cursor,String state,String error,Map<String,String> before,List<Receipt> receipts)throws Exception{var n=new Batch(b.batchId(),world,b.taskId(),b.intent(),b.agentId(),b.owner(),b.dimension(),b.actions(),cursor,state,error,before,receipts,b.revision()+1,b.round());store(n,b.revision());return n;}
    private static String key(UUID task,long intent){return task+"|"+intent;}
    public synchronized Planning beginPlanning(ManagedTask task)throws Exception{var k=key(task.taskId(),task.intentRevision());var old=planning(task.taskId(),task.intentRevision());if(old!=null&&(old.pending()||old.uncertain()||old.attempts()>=16))throw new IllegalStateException("WORLD_PLANNING_NOT_REPLAYABLE");var p=new Planning(task.taskId(),task.intentRevision(),old==null?1:old.attempts()+1,true,false,old==null?1:old.revision()+1);savePlanning(p,old==null?0:old.revision());return p;}
    public synchronized List<Planning> planningRecords(){return readPlanning("PLANNING_ALL");}
    public synchronized List<Planning> planningAttention(){return readPlanning("PLANNING_ATTENTION");}
    private List<Planning> readPlanning(String kind){try{var values=new ArrayList<Planning>();for(var row:repo.worldActionRows(world,null,null,null,kind,0,kind.equals("PLANNING_ATTENTION")?256:(int)WorldActionRetention.MAX_ROWS))values.add(json.readValue(row.payload(),Planning.class));return List.copyOf(values);}catch(Exception unavailable){throw new IllegalStateException("WORLD_PLANNING_HISTORY_UNAVAILABLE",unavailable);}}
    public synchronized void endPlanning(UUID task,long intent,boolean certain)throws Exception{var old=planning(task,intent);if(old==null||!old.pending())return;savePlanning(new Planning(task,intent,old.attempts(),false,!certain,old.revision()+1),old.revision());}
    public synchronized boolean planningUncertain(UUID task,long intent){var p=planning(task,intent);return p!=null&&p.uncertain();}
    public synchronized int planningAttempts(UUID task,long intent){var p=planning(task,intent);return p==null?0:p.attempts();}
    private void savePlanning(Planning p,long expected)throws Exception{if(!repo.worldActionCompareAndSet(world,"world_action_planning_v1",key(p.taskId(),p.intent()),expected,json.writeValueAsString(p),System.currentTimeMillis()).accepted())throw new IllegalStateException("WORLD_PLANNING_CAS");cachePlanning(p);}
    private void store(Batch b,long expected)throws Exception{if(!repo.worldActionCompareAndSet(world,NS,b.batchId().toString(),expected,json.writeValueAsString(b),System.currentTimeMillis()).accepted())throw new IllegalStateException("WORLD_ACTION_CAS");cacheBatch(b);}
    private Planning planning(UUID task,long intent){String k=key(task,intent);var p=planning.get(k);if(p==null)p=planningCache.get(k);if(p!=null)return p;try{var row=repo.get(world,WorldActionRetention.PLANNING,k);if(row.isEmpty())return null;p=json.readValue(row.get().payload(),Planning.class);cachePlanning(p);return p;}catch(Exception unavailable){throw new IllegalStateException("WORLD_PLANNING_HISTORY_UNAVAILABLE",unavailable);}}
    private void cachePlanning(Planning p){String k=key(p.taskId(),p.intent());if(p.pending()){planningCache.remove(k);planning.put(k,p);}else{planning.remove(k);planningCache.put(k,p);while(planningCache.size()>128)planningCache.remove(planningCache.keySet().iterator().next());}}
    public synchronized Map<String,WorldActionRetention.Usage> usage()throws Exception{return Map.of("batches",repo.worldActionUsage(world,NS),"planning",repo.worldActionUsage(world,WorldActionRetention.PLANNING));}
    private static void code(String code){if(code==null||!code.matches("[A-Z0-9_]{0,80}"))throw new IllegalArgumentException("WORLD_ACTION_CODE");}
    private static void bound(Map<String,String> values){if(values==null||values.size()>64||values.entrySet().stream().anyMatch(e->e.getKey()==null||e.getKey().length()>128||e.getValue()==null||e.getValue().length()>65536)||values.values().stream().mapToInt(String::length).sum()>131072)throw new IllegalArgumentException("WORLD_ACTION_EVIDENCE_LIMIT");}
    @Override public synchronized void close()throws Exception{repo.close();}
}
