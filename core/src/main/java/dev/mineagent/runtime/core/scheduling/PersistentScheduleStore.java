package dev.mineagent.runtime.core.scheduling;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.directory.ObjectDirectory;
import dev.mineagent.runtime.core.persistence.RetainedRows;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Explicit UTC/game-tick/recorded-event clocks, stable occurrence slots and a durable task outbox. */
public final class PersistentScheduleStore implements AutoCloseable {
    public record Context(ObjectDirectory.Scope scope,UUID serverEpoch,UUID operation){public Context{Objects.requireNonNull(scope);Objects.requireNonNull(serverEpoch);Objects.requireNonNull(operation);}}
    public record Definition(UUID id,Context creator,ScheduleSpec spec,long start,long expires,long cursor,long tickHighWater,long conditionCursor,int skipped,String state,long revision,String error){}
    public record DeliveryResult(UUID audienceSnapshot,UUID batchId,int recipients,int eligible){public DeliveryResult{Objects.requireNonNull(audienceSnapshot);if(recipients<0||recipients>64||eligible<0||eligible>recipients||(recipients==0)!=(batchId==null))throw new IllegalArgumentException("SCHEDULE_DELIVERY_RESULT");}}
    public record Occurrence(UUID id,UUID scheduleId,long index,long definitionRevision,UUID conditionEvent,String state,String authority,UUID taskId,int modelAttempts,int maxModelCalls,long due,long recordedAt,String error,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String scriptResult,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DeliveryResult deliveryResult){
        public Occurrence(UUID id,UUID scheduleId,long index,long definitionRevision,UUID conditionEvent,String state,String authority,UUID taskId,int modelAttempts,int maxModelCalls,long due,long recordedAt,String error,String scriptResult){this(id,scheduleId,index,definitionRevision,conditionEvent,state,authority,taskId,modelAttempts,maxModelCalls,due,recordedAt,error,scriptResult,null);}
        public Occurrence(UUID id,UUID scheduleId,long index,long definitionRevision,UUID conditionEvent,String state,String authority,UUID taskId,int modelAttempts,int maxModelCalls,long due,long recordedAt,String error){this(id,scheduleId,index,definitionRevision,conditionEvent,state,authority,taskId,modelAttempts,maxModelCalls,due,recordedAt,error,"");}
        public Occurrence{scriptResult=scriptResult==null?"":scriptResult;if(!scriptResult.isEmpty())scriptResult=dev.mineagent.runtime.core.events.ScriptEventConsumer.normalizeResult(scriptResult);}
    }
    public record PushDelivery(UUID id,UUID occurrenceId,UUID scheduleId,dev.mineagent.runtime.api.ui.UiProtocol.Session session,String state,long createdAt,long expiresAt,int attempts,long lastAttempt,long readRevision,String error){}
    public record Signal(long sequence,UUID eventId){public Signal{if(sequence<0||sequence>0&&eventId==null)throw new IllegalArgumentException("SCHEDULE_SIGNAL");}}
    private record Operation(Context context,String fingerprint,UUID scheduleId){}
    public record ManagementContext(UUID world,UUID owner){public ManagementContext{Objects.requireNonNull(world);Objects.requireNonNull(owner);}}
    public record ManagementPage(int total,int offset,int nextOffset,boolean more,List<Definition> definitions){public ManagementPage{definitions=List.copyOf(definitions);}}
    public record RetentionCount(long hot,long archived,long total){}
    public record ManagementReceipt(UUID operation,UUID schedule,String action,String state,long revision,int archivedDefinitions,int archivedOccurrences,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer archivedPushDeliveries){
        public ManagementReceipt(UUID operation,UUID schedule,String action,String state,long revision,int archivedDefinitions,int archivedOccurrences){this(operation,schedule,action,state,revision,archivedDefinitions,archivedOccurrences,null);}
    }
    public record ManagementResult(ManagementReceipt receipt,boolean duplicate,String currentState,long currentRevision){}
    private record ManagementOperation(UUID owner,String fingerprint,ManagementReceipt receipt){}
    private static final String TERMINAL_OCCURRENCES="'RECORDED','COMPLETED','CANCELLED','FAILED','INTERRUPTED','MODEL_BUDGET_EXHAUSTED','SCRIPT_HANDLED','SCRIPT_CANCELLED','SCRIPT_INTERRUPTED','PUSH_READ_DELIVERED','PUSH_NO_RECIPIENTS','PUSH_PARTIAL_OR_FAILED','PUSH_INTERRUPTED','DELIVERY_RECORDED','DELIVERY_NO_RECIPIENTS','DELIVERY_INTERRUPTED','DELIVERY_CANCELLED'";
    private static final Set<String> TERMINAL_DEFINITIONS=Set.of("CANCELLED","EXPIRED","FINISHED");
    public interface Port{void authorize(Context context);String authority(Definition definition);Signal signal(Definition definition);
        default void authorizeManagement(ManagementContext context,Definition definition,boolean activate){throw new SecurityException("SCHEDULE_MANAGEMENT_UNAVAILABLE");}
        default boolean waitingForTarget(Definition definition){return false;}
    }
    private final Connection db;private final UUID world;private final LongSupplier wall,ticks;private final Port port;private final FileChannel channel;private final FileLock lock;private final ObjectMapper json=new ObjectMapper();
    private PersistentScheduleStore(Connection db,UUID world,LongSupplier wall,LongSupplier ticks,Port port,FileChannel channel,FileLock lock)throws Exception{
        this.db=db;this.world=world;this.wall=wall;this.ticks=ticks;this.port=port;this.channel=channel;this.lock=lock;
        try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE IF NOT EXISTS mineagent_schedules_v1(world TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_schedule_occurrences_v1(world TEXT NOT NULL,id TEXT NOT NULL,schedule TEXT NOT NULL,slot INTEGER NOT NULL,state TEXT NOT NULL,task TEXT,payload TEXT NOT NULL,PRIMARY KEY(world,id),UNIQUE(world,schedule,slot),UNIQUE(world,task))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_schedule_operations_v1(world TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_queue_v1 ON mineagent_schedule_occurrences_v1(world,state)");}
        tx(()->{try(var statement=db.createStatement()){
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_schedule_management_v1(world TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            statement.execute("CREATE TABLE IF NOT EXISTS mineagent_schedule_push_v1(world TEXT NOT NULL,id TEXT NOT NULL,occurrence TEXT NOT NULL,schedule TEXT NOT NULL,state TEXT NOT NULL,last_attempt INTEGER NOT NULL,expires_at INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_push_queue_v1 ON mineagent_schedule_push_v1(world,state,last_attempt)");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_push_occurrence_v1 ON mineagent_schedule_push_v1(world,occurrence)");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_push_owner_v1 ON mineagent_schedule_push_v1(world,schedule)");
            statement.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_owner_state_v1 ON mineagent_schedules_v1(world,owner,state)");
        }
            RetainedRows.initialize(db,world,List.of("mineagent_schedules_v1","mineagent_schedule_occurrences_v1","mineagent_schedule_operations_v1","mineagent_schedule_management_v1","mineagent_schedule_push_v1"));
            try(var statement=db.createStatement()){
                statement.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_owner_retention_v1 ON mineagent_schedules_v1(world,owner,archived)");
                statement.execute("CREATE INDEX IF NOT EXISTS mineagent_schedule_history_retention_v1 ON mineagent_schedule_occurrences_v1(world,schedule,archived,state)");
            }return null;
        });
        tx(()->{for(var o:allOccurrences())if(Set.of("QUEUED","CLAIMED","DISPATCHED").contains(o.state()))writeOccurrence(change(o,"INTERRUPTED","SERVER_RESTART_NO_REPLAY"));else if(Set.of("SCRIPT_QUEUED","SCRIPT_DISPATCHING").contains(o.state()))writeOccurrence(change(o,"SCRIPT_INTERRUPTED","SERVER_RESTART_NO_SCRIPT_REPLAY"));else if(Set.of("PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING").contains(o.state()))writeOccurrence(change(o,"PUSH_INTERRUPTED","SERVER_RESTART_NO_PUSH_REPLAY"));else if(Set.of("DELIVERY_QUEUED","DELIVERY_DISPATCHING").contains(o.state()))writeOccurrence(change(o,"DELIVERY_INTERRUPTED","SERVER_RESTART_NO_DELIVERY_REPLAY"));
            for(var d:allDefinitions())if(d.state().equals("ACTIVE")&&d.spec().kind().equals("WALL_PERIODIC")&&d.spec().missedPolicy().equals("SKIP")&&wall.getAsLong()>=d.start()){
                long index=Math.min(d.spec().maxOccurrences()-1,Math.floorDiv(wall.getAsLong()-d.start(),d.spec().period()));if(index>d.cursor())writeDefinition(update(d,index,d.tickHighWater(),d.conditionCursor(),d.skipped()+(int)(index-d.cursor()),d.state(),d.revision(),"SKIPPED_RESTART"));}
            return null;});
    }
    public static PersistentScheduleStore open(Path path,UUID world,LongSupplier wall,LongSupplier ticks,Port port)throws Exception{
        Objects.requireNonNull(world);Objects.requireNonNull(wall);Objects.requireNonNull(ticks);Objects.requireNonNull(port);Path p=path.toAbsolutePath().normalize();Files.createDirectories(p.getParent());var channel=FileChannel.open(p.resolveSibling(p.getFileName()+".schedule-"+world+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;Connection db=null;
        try{try{lock=channel.tryLock();}catch(OverlappingFileLockException e){throw new IllegalStateException("SCHEDULE_STORE_ALREADY_OPEN",e);}if(lock==null)throw new IllegalStateException("SCHEDULE_STORE_ALREADY_OPEN");db=DriverManager.getConnection("jdbc:sqlite:"+p);return new PersistentScheduleStore(db,world,wall,ticks,port,channel,lock);}catch(Exception e){if(db!=null)try{db.close();}catch(Exception x){e.addSuppressed(x);}if(lock!=null)try{lock.close();}catch(Exception x){e.addSuppressed(x);}try{channel.close();}catch(Exception x){e.addSuppressed(x);}throw e;}
    }
    private void authorize(Context c){if(!world.equals(c.scope().worldId()))throw new SecurityException("SCHEDULE_WORLD_MISMATCH");port.authorize(c);}
    private Definition owned(Context c,UUID id)throws Exception{var d=definition(id);if(d==null||!d.creator().scope().ownerId().equals(c.scope().ownerId())||!d.creator().scope().agentId().equals(c.scope().agentId()))throw new SecurityException("SCHEDULE_NOT_OWNED");return d;}
    private String fingerprint(Context c,String kind,Object value)throws Exception{var s=c.scope();return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((world+"|"+s.ownerId()+"|"+s.agentId()+"|"+s.taskId()+"|"+s.intentRevision()+"|"+kind+"|"+json.writeValueAsString(value)).getBytes(StandardCharsets.UTF_8)));}
    private UUID stable(String prefix,String id){return UUID.nameUUIDFromBytes((world+"|"+prefix+"|"+id).getBytes(StandardCharsets.UTF_8));}
    public synchronized Definition create(Context c,ScheduleSpec spec)throws Exception{
        authorize(c);return tx(()->{authorize(c);String fp=fingerprint(c,"CREATE",spec);var old=operation(c.operation());if(old!=null){if(!old.fingerprint().equals(fp))throw new IllegalArgumentException("SCHEDULE_OPERATION_REUSED");return owned(c,old.scheduleId());}
            budget("mineagent_schedule_operations_v1",8192);budget("mineagent_schedules_v1",128);if(ownerHotCount(c)>=32){archiveDefinitions(c.scope().ownerId(),c.scope().agentId(),128);if(ownerHotCount(c)>=32)throw new IllegalStateException("SCHEDULE_OWNER_BUDGET");}
            long now=wall.getAsLong(),tick=ticks.getAsLong(),start=spec.kind().startsWith("WALL")?(spec.atMillis()==null?Math.addExact(now,spec.delay()):spec.atMillis()):spec.kind().startsWith("TICK")?Math.addExact(tick,spec.delay()):0;
            long last=spec.kind().startsWith("WALL")?Math.addExact(start,Math.multiplyExact(spec.period(),spec.maxOccurrences()-1)):now;long expires=Math.addExact(last,spec.ttlMillis());
            var d=new Definition(stable("schedule",c.operation().toString()),c,spec,start,expires,-1,tick,0,0,"ACTIVE",1,"");
            if((spec.script()!=null||spec.push()!=null||spec.delivery()!=null)&&port.authority(d)==null)throw new SecurityException("SCHEDULE_SCRIPT_TARGET_UNAVAILABLE");
            if(spec.kind().equals("EVENT_CONDITION")){var initial=port.signal(d);if(!spec.initiallyMatched())d=update(d,-1,tick,initial.sequence(),0,"ACTIVE",1,"");}
            writeDefinition(d);writeOperation(c.operation(),new Operation(c,fp,d.id()));authorize(c);if((spec.script()!=null||spec.push()!=null||spec.delivery()!=null)&&port.authority(d)==null)throw new SecurityException("SCHEDULE_SCRIPT_TARGET_UNAVAILABLE");return d;});
    }
    public synchronized Definition state(Context c,UUID id,long expected,String target)throws Exception{
        authorize(c);if(!Set.of("ACTIVE","PAUSED","CANCELLED").contains(target))throw new IllegalArgumentException("SCHEDULE_STATE");return tx(()->{authorize(c);var d=owned(c,id);String fp=fingerprint(c,"STATE",List.of(id,expected,target));var old=operation(c.operation());if(old!=null){if(!fp.equals(old.fingerprint()))throw new IllegalArgumentException("SCHEDULE_OPERATION_REUSED");return d;}
            if(d.revision()!=expected||!Set.of("ACTIVE","PAUSED").contains(d.state())||wall.getAsLong()>=d.expires())throw new IllegalStateException("SCHEDULE_STALE");budget("mineagent_schedule_operations_v1",8192);
            var value=transition(d,target);writeOperation(c.operation(),new Operation(c,fp,id));return value;});
    }
    private long ownerHotCount(Context context)throws Exception{return allDefinitions().stream().filter(d->d.creator().scope().ownerId().equals(context.scope().ownerId())&&d.creator().scope().agentId().equals(context.scope().agentId())).count();}
    private Definition transition(Definition d,String target)throws Exception{
        if(target.equals("ACTIVE")&&(d.spec().script()!=null||d.spec().push()!=null||d.spec().delivery()!=null)&&port.authority(d)==null)throw new SecurityException("SCHEDULE_SCRIPT_TARGET_UNAVAILABLE");
        long cursor=d.cursor();int skipped=d.skipped();if(target.equals("ACTIVE")&&d.spec().kind().equals("WALL_PERIODIC")&&d.spec().missedPolicy().equals("SKIP")&&wall.getAsLong()>=d.start()){long index=Math.min(d.spec().maxOccurrences()-1,Math.floorDiv(wall.getAsLong()-d.start(),d.spec().period()));if(index>cursor){skipped+=(int)(index-cursor);cursor=index;}}
        long condition=d.conditionCursor();if(target.equals("ACTIVE")&&d.spec().kind().equals("EVENT_CONDITION"))condition=port.signal(d).sequence();
        var value=update(d,cursor,d.tickHighWater(),condition,skipped,target,d.revision()+1,"");writeDefinition(value);cancelPending(d.id(),"SCHEDULE_CHANGED");return value;
    }
    public synchronized void poll()throws Exception{tx(()->{for(var d:allDefinitions()){
            if(!d.state().equals("ACTIVE"))continue;long now=wall.getAsLong(),tick=Math.max(d.tickHighWater(),ticks.getAsLong());
            if(now>=d.expires()){writeDefinition(update(d,d.cursor(),tick,d.conditionCursor(),d.skipped(),"EXPIRED",d.revision()+1,"EXPIRED"));cancelPending(d.id(),"SCHEDULE_EXPIRED");continue;}
            String authority;try{authority=port.authority(d);}catch(RuntimeException failure){authority=null;}if(authority==null){if(port.waitingForTarget(d))continue;writeDefinition(update(d,d.cursor(),tick,d.conditionCursor(),d.skipped(),"PAUSED",d.revision()+1,"AUTHORITY_REVOKED"));cancelPending(d.id(),"SCHEDULE_AUTHORITY_CHANGED");continue;}
            if(d.cursor()>=d.spec().maxOccurrences()-1){if(pending(d.id())==0)writeDefinition(update(d,d.cursor(),tick,d.conditionCursor(),d.skipped(),"FINISHED",d.revision(),d.error()));continue;}
            long index=-1,due=0,condition=d.conditionCursor();UUID signal=null;int skipped=d.skipped();
            if(d.spec().kind().equals("EVENT_CONDITION")){Signal observed;try{observed=port.signal(d);}catch(RuntimeException failure){writeDefinition(update(d,d.cursor(),tick,condition,d.skipped(),"PAUSED",d.revision()+1,"CONDITION_UNAVAILABLE"));cancelPending(d.id(),"CONDITION_UNAVAILABLE");continue;}if(observed.sequence()>condition){index=0;due=now;condition=observed.sequence();signal=observed.eventId();}}
            else{long time=d.spec().kind().startsWith("WALL")?now:tick;if(time>=d.start()){
                    index=d.spec().kind().endsWith("PERIODIC")?Math.min(d.spec().maxOccurrences()-1,Math.floorDiv(time-d.start(),d.spec().period())):0;
                    if(index<=d.cursor())index=-1;else{due=Math.addExact(d.start(),Math.multiplyExact(index,d.spec().period()));skipped+=(int)Math.max(0,index-d.cursor()-1);
                        if(d.spec().kind().endsWith("PERIODIC")&&d.spec().missedPolicy().equals("SKIP")&&time-due>(d.spec().kind().startsWith("WALL")?d.spec().lateness():0)){writeDefinition(update(d,index,tick,condition,skipped+1,d.state(),d.revision(),"SKIPPED_LATE"));continue;}}
                }}
            if(index<0)continue;
            budget("mineagent_schedule_occurrences_v1",8192);UUID id=stable("occurrence",d.id()+"|"+index);UUID task=(d.spec().mode().equals("AGENT_WAKE")||d.spec().delivery()!=null)?UUID.nameUUIDFromBytes(("task-start|"+world+"|"+d.creator().scope().ownerId()+"|"+id).getBytes(StandardCharsets.UTF_8)):null;
            writeOccurrence(new Occurrence(id,d.id(),index,d.revision(),signal,d.spec().delivery()!=null?"DELIVERY_QUEUED":d.spec().push()!=null?"PUSH_QUEUED":d.spec().script()!=null?"SCRIPT_QUEUED":task==null?"RECORDED":"QUEUED",authority,task,0,d.spec().maxModelCalls(),due,now,""));writeDefinition(update(d,index,tick,condition,skipped,d.state(),d.revision(),""));
        }return null;});}
    private Definition update(Definition d,long cursor,long tick,long condition,int skipped,String state,long revision,String error){return new Definition(d.id(),d.creator(),d.spec(),d.start(),d.expires(),cursor,tick,condition,skipped,state,revision,error);}
    private boolean valid(Occurrence o)throws Exception{var d=definition(o.scheduleId());if(d==null||!d.state().equals("ACTIVE")||d.revision()!=o.definitionRevision()||wall.getAsLong()>=d.expires())return false;String token=port.authority(d);return token!=null&&token.equals(o.authority());}
    public synchronized Optional<Occurrence> claimScript()throws Exception{return tx(()->{
        // A Native caller with a pending completion never claims another handler. Orphan markers are unknown, not replayable.
        var orphaned=new ArrayList<Occurrence>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND state='SCRIPT_DISPATCHING' LIMIT 8")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())orphaned.add(json.readValue(rows.getString(1),Occurrence.class));}}
        for(var o:orphaned)writeOccurrence(change(o,"SCRIPT_INTERRUPTED","SCRIPT_ORPHAN_MARKER_NO_REPLAY"));
        var queued=new ArrayList<Occurrence>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND state='SCRIPT_QUEUED' ORDER BY rowid LIMIT 8")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())queued.add(json.readValue(rows.getString(1),Occurrence.class));}}
        for(var o:queued){var d=definition(o.scheduleId());if(d==null||d.spec().script()==null||!valid(o)){writeOccurrence(change(o,"SCRIPT_CANCELLED","SCHEDULE_SCRIPT_AUTHORITY_CHANGED"));continue;}var marker=change(o,"SCRIPT_DISPATCHING","");writeOccurrence(marker);return Optional.of(marker);}return Optional.empty();
    });}
    public synchronized boolean permitScript(UUID id)throws Exception{var o=occurrence(id);return o!=null&&o.state().equals("SCRIPT_DISPATCHING")&&definition(o.scheduleId()).spec().script()!=null&&valid(o);}
    public synchronized void finishScript(UUID id,String result,String error)throws Exception{
        String normalized=result==null||result.isEmpty()?"":dev.mineagent.runtime.core.events.ScriptEventConsumer.normalizeResult(result);
        tx(()->{var o=occurrence(id);if(o==null||!o.state().equals("SCRIPT_DISPATCHING"))return null;boolean handled=error.isEmpty()&&!normalized.isEmpty()&&valid(o);String code=handled?"":error.isEmpty()?"SCHEDULE_SCRIPT_COMPLETION_UNCERTAIN":error;
            writeOccurrence(new Occurrence(o.id(),o.scheduleId(),o.index(),o.definitionRevision(),o.conditionEvent(),handled?"SCRIPT_HANDLED":"SCRIPT_INTERRUPTED",o.authority(),null,0,0,o.due(),o.recordedAt(),code,normalized));return null;});
    }
    public synchronized Optional<Occurrence> claim()throws Exception{if(queued().isEmpty())return Optional.empty();return tx(()->{for(var o:queued()){if(!valid(o)){writeOccurrence(change(o,"CANCELLED","SCHEDULE_AUTHORITY_CHANGED"));continue;}var claimed=change(o,"CLAIMED","");writeOccurrence(claimed);return Optional.of(claimed);}return Optional.empty();});}
    public synchronized Optional<Occurrence> claimDelivery()throws Exception{return tx(()->{
        var rows=new ArrayList<Occurrence>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND state IN ('DELIVERY_QUEUED','DELIVERY_DISPATCHING') ORDER BY rowid LIMIT 8")){query.setString(1,world.toString());try(var result=query.executeQuery()){while(result.next())rows.add(json.readValue(result.getString(1),Occurrence.class));}}
        for(var o:rows){if(o.state().equals("DELIVERY_DISPATCHING")||!valid(o)||definition(o.scheduleId()).spec().delivery()==null){writeOccurrence(change(o,"DELIVERY_INTERRUPTED","DELIVERY_UNKNOWN_OR_STALE_NO_REPLAY"));continue;}var claimed=change(o,"DELIVERY_DISPATCHING","");writeOccurrence(claimed);return Optional.of(claimed);}return Optional.empty();
    });}
    public synchronized void finishDelivery(UUID id,DeliveryResult result,String error)throws Exception{tx(()->{var o=occurrence(id);if(o==null||!o.state().equals("DELIVERY_DISPATCHING"))return null;
        boolean recorded=error.isEmpty()&&result!=null;String state=recorded?(result.recipients()==0?"DELIVERY_NO_RECIPIENTS":"DELIVERY_RECORDED"):"DELIVERY_INTERRUPTED";
        writeOccurrence(new Occurrence(o.id(),o.scheduleId(),o.index(),o.definitionRevision(),o.conditionEvent(),state,o.authority(),o.taskId(),0,0,o.due(),o.recordedAt(),recorded?"":error.isEmpty()?"DELIVERY_OUTCOME_UNKNOWN":error,"",result));return null;
    });}
    public synchronized Occurrence managementOccurrence(ManagementContext context,UUID schedule,UUID id)throws Exception{managementInspect(context,schedule);var o=occurrence(id);if(o==null||!o.scheduleId().equals(schedule))throw new SecurityException("SCHEDULE_OCCURRENCE_NOT_OWNED");return o;}
    public synchronized Optional<Occurrence> claimPush()throws Exception{return tx(()->{
        var candidates=new ArrayList<Occurrence>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND state IN ('PUSH_QUEUED','PUSH_DISPATCHING') ORDER BY rowid LIMIT 8")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())candidates.add(json.readValue(rows.getString(1),Occurrence.class));}}
        for(var o:candidates){if(o.state().equals("PUSH_DISPATCHING")||!valid(o)||definition(o.scheduleId()).spec().push()==null){writeOccurrence(change(o,"PUSH_INTERRUPTED","SCHEDULE_PUSH_UNKNOWN_OR_STALE"));continue;}var marker=change(o,"PUSH_DISPATCHING","");writeOccurrence(marker);return Optional.of(marker);}return Optional.empty();
    });}
    public synchronized void preparePush(UUID id,List<dev.mineagent.runtime.api.ui.UiProtocol.Session> targets)throws Exception{
        var selected=List.copyOf(targets);if(selected.size()>32||selected.stream().map(s->s.sessionId()).distinct().count()!=selected.size())throw new IllegalArgumentException("SCHEDULE_PUSH_RECIPIENT_BUDGET");
        tx(()->{var o=occurrence(id);if(o==null||!o.state().equals("PUSH_DISPATCHING")||!valid(o))throw new SecurityException("SCHEDULE_PUSH_STALE");var d=definition(o.scheduleId());var destination=Objects.requireNonNull(d.spec().push());
            for(var session:selected){var b=session.binding();if(!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(b)||!b.worldId().equals(world)||!b.ownerPackageId().equals(destination.packageId())||b.packageRevision()!=destination.packageRevision()||!b.entryPath().equals(destination.entryPath())||!b.targetObjectId().equals("world:"+destination.instanceId())||session.status()!=dev.mineagent.runtime.api.ui.UiProtocol.Status.RENDERED)throw new SecurityException("SCHEDULE_PUSH_TARGET_SCOPE");}
            for(var session:selected){budget("mineagent_schedule_push_v1",8192);UUID delivery=stable("schedule-state-push",id+"|"+session.sessionId()+"|"+session.pageGeneration()+"|"+session.controlEpoch());long at=wall.getAsLong();writePush(new PushDelivery(delivery,id,d.id(),session,"PLANNED",at,Math.min(Math.min(at+15000,session.expiresAtMillis()),d.expires()),0,0,0,""));}
            writeOccurrence(change(o,selected.isEmpty()?"PUSH_NO_RECIPIENTS":"PUSH_WAITING",""));return null;
        });
    }
    public synchronized void failPush(UUID id)throws Exception{tx(()->{var o=occurrence(id);if(o!=null&&Set.of("PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING").contains(o.state()))writeOccurrence(change(o,"PUSH_INTERRUPTED","SCHEDULE_PUSH_DISPATCH_UNCERTAIN"));return null;});}
    private PushDelivery push(UUID id)throws Exception{return read("mineagent_schedule_push_v1",id,PushDelivery.class);}
    private static boolean pushTerminal(PushDelivery row){return Set.of("READ_DELIVERED","EXPIRED","REJECTED","INTERRUPTED").contains(row.state());}
    private boolean pushCurrent(PushDelivery row)throws Exception{var o=occurrence(row.occurrenceId());return !pushTerminal(row)&&wall.getAsLong()<row.expiresAt()&&o!=null&&o.state().equals("PUSH_WAITING")&&valid(o);}
    private PushDelivery pushChange(PushDelivery row,String state,int attempts,long last,long revision,String error){return new PushDelivery(row.id(),row.occurrenceId(),row.scheduleId(),row.session(),state,row.createdAt(),row.expiresAt(),attempts,last,revision,error);}
    private void writePush(PushDelivery row)throws Exception{try(var query=db.prepareStatement("INSERT INTO mineagent_schedule_push_v1(world,id,occurrence,schedule,state,last_attempt,expires_at,payload) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,last_attempt=excluded.last_attempt,expires_at=excluded.expires_at,payload=excluded.payload")){query.setString(1,world.toString());query.setString(2,row.id().toString());query.setString(3,row.occurrenceId().toString());query.setString(4,row.scheduleId().toString());query.setString(5,row.state());query.setLong(6,row.lastAttempt());query.setLong(7,row.expiresAt());query.setString(8,json.writeValueAsString(row));query.executeUpdate();}}
    public synchronized List<PushDelivery> pushDeliveries(UUID occurrence)throws Exception{var result=new ArrayList<PushDelivery>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_push_v1 WHERE world=? AND occurrence=? ORDER BY rowid")){query.setString(1,world.toString());query.setString(2,occurrence.toString());try(var rows=query.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),PushDelivery.class));}}return List.copyOf(result);}
    private void finishPushIfSettled(UUID id)throws Exception{var o=occurrence(id);if(o==null||!o.state().equals("PUSH_WAITING"))return;var rows=pushDeliveries(id);if(rows.isEmpty()){writeOccurrence(change(o,"PUSH_INTERRUPTED","SCHEDULE_PUSH_RECEIPTS_MISSING"));return;}if(rows.stream().anyMatch(row->!pushTerminal(row)))return;writeOccurrence(change(o,rows.stream().allMatch(row->row.state().equals("READ_DELIVERED"))?"PUSH_READ_DELIVERED":"PUSH_PARTIAL_OR_FAILED",""));}
    public synchronized List<PushDelivery> pushCandidates(int limit)throws Exception{
        if(limit<1||limit>16)throw new IllegalArgumentException("SCHEDULE_PUSH_PUMP_BUDGET");return tx(()->{var rows=new ArrayList<PushDelivery>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_push_v1 WHERE world=? AND state IN ('PLANNED','SENT','READ_PRODUCED') ORDER BY last_attempt,rowid LIMIT ?")){query.setString(1,world.toString());query.setInt(2,limit);try(var result=query.executeQuery()){while(result.next())rows.add(json.readValue(result.getString(1),PushDelivery.class));}}
            var due=new ArrayList<PushDelivery>();for(var row:rows){if(!pushCurrent(row)){writePush(pushChange(row,"EXPIRED",row.attempts(),row.lastAttempt(),row.readRevision(),"SCHEDULE_PUSH_STALE_OR_EXPIRED"));finishPushIfSettled(row.occurrenceId());}
                else if(wall.getAsLong()-row.lastAttempt()>=1000){if(row.attempts()>=3){writePush(pushChange(row,"EXPIRED",row.attempts(),row.lastAttempt(),row.readRevision(),"SCHEDULE_PUSH_NO_READ_ACK"));finishPushIfSettled(row.occurrenceId());}else due.add(row);}}
            return List.copyOf(due);
        });
    }
    public synchronized PushDelivery markPushSent(UUID id)throws Exception{return tx(()->{var row=push(id);if(row==null||!pushCurrent(row)||row.attempts()>=3)throw new SecurityException("SCHEDULE_PUSH_SEND_STALE");var sent=pushChange(row,row.state().equals("READ_PRODUCED")?"READ_PRODUCED":"SENT",row.attempts()+1,wall.getAsLong(),row.readRevision(),"");writePush(sent);return sent;});}
    public synchronized void rejectPush(UUID id)throws Exception{tx(()->{var row=push(id);if(row!=null&&!pushTerminal(row)){writePush(pushChange(row,"REJECTED",row.attempts(),row.lastAttempt(),row.readRevision(),"SCHEDULE_PUSH_VIEW_UNAVAILABLE"));finishPushIfSettled(row.occurrenceId());}return null;});}
    public synchronized PushDelivery requirePushRead(UUID id,dev.mineagent.runtime.api.ui.UiProtocol.Session session)throws Exception{var row=push(id);if(row==null||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(row.session(),session)||!Set.of("SENT","READ_PRODUCED").contains(row.state())||!pushCurrent(row))throw new SecurityException("SCHEDULE_PUSH_READ_SCOPE");return row;}
    public synchronized void pushReadProduced(List<UUID> ids,dev.mineagent.runtime.api.ui.UiProtocol.Session session,long revision)throws Exception{
        if(revision<1||ids.isEmpty()||ids.size()>16||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("SCHEDULE_PUSH_READ_BATCH");tx(()->{for(var id:ids){var row=requirePushRead(id,session);writePush(pushChange(row,"READ_PRODUCED",row.attempts(),row.lastAttempt(),revision,""));}return null;});
    }
    public synchronized void acknowledgePushRead(UUID id,dev.mineagent.runtime.api.ui.UiProtocol.Session session)throws Exception{tx(()->{var row=push(id);if(row==null||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(row.session(),session))throw new SecurityException("SCHEDULE_PUSH_ACK_SCOPE");if(row.state().equals("READ_DELIVERED"))return null;if(!row.state().equals("READ_PRODUCED")||!pushCurrent(row))throw new SecurityException("SCHEDULE_PUSH_ACK_STALE");writePush(pushChange(row,"READ_DELIVERED",row.attempts(),row.lastAttempt(),row.readRevision(),""));finishPushIfSettled(row.occurrenceId());return null;});}
    public synchronized void dispatched(UUID id)throws Exception{tx(()->{var o=occurrence(id);if(o==null||!o.state().equals("CLAIMED")||!valid(o))throw new IllegalStateException("SCHEDULE_DISPATCH_STALE");writeOccurrence(change(o,"DISPATCHED",""));return null;});}
    public synchronized void failed(UUID id,String error)throws Exception{tx(()->{var o=occurrence(id);if(o!=null&&Set.of("QUEUED","CLAIMED","DISPATCHED").contains(o.state()))writeOccurrence(change(o,"INTERRUPTED",error));return null;});}
    public synchronized boolean permitTask(UUID task)throws Exception{var o=forTask(task).orElse(null);return o==null||(o.state().equals("DISPATCHED")||o.state().equals("DELIVERY_DISPATCHING"))&&valid(o);}
    public synchronized boolean reserveModel(UUID task)throws Exception{return tx(()->{var o=forTask(task).orElse(null);if(o==null)return true;if(!o.state().equals("DISPATCHED")||!valid(o))return false;if(o.modelAttempts()>=o.maxModelCalls()){writeOccurrence(change(o,"MODEL_BUDGET_EXHAUSTED","SCHEDULE_MODEL_BUDGET"));return false;}writeOccurrence(new Occurrence(o.id(),o.scheduleId(),o.index(),o.definitionRevision(),o.conditionEvent(),o.state(),o.authority(),o.taskId(),o.modelAttempts()+1,o.maxModelCalls(),o.due(),o.recordedAt(),o.error()));return true;});}
    public synchronized void taskFinished(UUID task,String state)throws Exception{tx(()->{var o=forTask(task).orElse(null);if(o!=null&&o.state().equals("DISPATCHED")&&Set.of("COMPLETED","CANCELLED","FAILED").contains(state))writeOccurrence(change(o,state,state.equals("COMPLETED")?"":"TASK_STOPPED"));return null;});}
    public synchronized Definition inspect(Context c,UUID id)throws Exception{authorize(c);return owned(c,id);}
    public synchronized List<Occurrence> occurrences(Context c,UUID id)throws Exception{authorize(c);owned(c,id);return historyRows(id,0,32,false);}
    public synchronized List<Occurrence> history(Context c,UUID id,int offset,int limit)throws Exception{authorize(c);owned(c,id);var values=historyRows(id,offset,limit,false);authorize(c);return values;}
    public synchronized long historyCount(Context c,UUID id)throws Exception{authorize(c);owned(c,id);return retentionCount("mineagent_schedule_occurrences_v1","schedule",id).total();}
    private List<Occurrence> historyRows(UUID id,int offset,int limit,boolean newest)throws Exception{
        if(offset<0||offset>RetainedRows.MAX_RETAINED_ROWS||limit<1||limit>32)throw new IllegalArgumentException("SCHEDULE_HISTORY_PAGE");var values=new ArrayList<Occurrence>();
        try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND schedule=? ORDER BY slot "+(newest?"DESC":"ASC")+" LIMIT ? OFFSET ?")){query.setString(1,world.toString());query.setString(2,id.toString());query.setInt(3,limit);query.setInt(4,offset);try(var rows=query.executeQuery()){while(rows.next())values.add(json.readValue(rows.getString(1),Occurrence.class));}}return List.copyOf(values);
    }
    private void management(ManagementContext context,Definition d,boolean activate){if(!world.equals(context.world())||d!=null&&!context.owner().equals(d.creator().scope().ownerId()))throw new SecurityException("SCHEDULE_MANAGEMENT_OWNER");port.authorizeManagement(context,d,activate);}
    public synchronized Definition managementInspect(ManagementContext context,UUID id)throws Exception{management(context,null,false);var d=definition(id);if(d==null)throw new IllegalStateException("SCHEDULE_MISSING");management(context,d,false);return d;}
    public synchronized ManagementPage managementList(ManagementContext context,String state,int offset,int limit)throws Exception{
        management(context,null,false);if(!Set.of("ALL","ACTIVE","PAUSED","CANCELLED","EXPIRED","FINISHED").contains(state)||offset<0||offset>RetainedRows.MAX_RETAINED_ROWS||limit<1||limit>16)throw new IllegalArgumentException("SCHEDULE_MANAGEMENT_PAGE");
        String filter=" WHERE world=? AND owner=? AND (?='ALL' OR state=?)";int total;var values=new ArrayList<Definition>();
        try(var query=db.prepareStatement("SELECT COUNT(*) FROM mineagent_schedules_v1"+filter)){managementFilter(query,context,state);try(var rows=query.executeQuery()){total=rows.next()?rows.getInt(1):0;}}
        try(var query=db.prepareStatement("SELECT payload FROM mineagent_schedules_v1"+filter+" ORDER BY rowid DESC LIMIT ? OFFSET ?")){managementFilter(query,context,state);query.setInt(5,limit);query.setInt(6,offset);try(var rows=query.executeQuery()){while(rows.next())values.add(json.readValue(rows.getString(1),Definition.class));}}
        management(context,null,false);return new ManagementPage(total,offset,offset+values.size(),total>offset+values.size(),values);
    }
    private void managementFilter(PreparedStatement query,ManagementContext context,String state)throws Exception{query.setString(1,world.toString());query.setString(2,context.owner().toString());query.setString(3,state);query.setString(4,state);}
    public synchronized List<Occurrence> managementHistory(ManagementContext context,UUID id,int offset,int limit)throws Exception{managementInspect(context,id);if(limit>8)throw new IllegalArgumentException("SCHEDULE_HISTORY_PAGE");var result=historyRows(id,offset,limit,true);management(context,definition(id),false);return result;}
    public synchronized RetentionCount managementOwnerRetention(ManagementContext context)throws Exception{management(context,null,false);return retentionCount("mineagent_schedules_v1","owner",context.owner());}
    private RetentionCount retentionCount(String table,String column,UUID id)throws Exception{
        try(var query=db.prepareStatement("SELECT COUNT(*),COALESCE(SUM(archived),0) FROM "+table+" WHERE world=? AND "+column+"=?")){query.setString(1,world.toString());query.setString(2,id.toString());try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("RETENTION_USAGE_MISSING");long total=rows.getLong(1),archived=rows.getLong(2);return new RetentionCount(total-archived,archived,total);}}
    }
    public synchronized boolean archivedDefinition(UUID id)throws Exception{return RetainedRows.archived(db,world,"mineagent_schedules_v1",id.toString());}
    public synchronized Map<String,Object> managementRetention(ManagementContext context,UUID id)throws Exception{
        var d=managementInspect(context,id);boolean archived=archivedDefinition(id);var count=retentionCount("mineagent_schedule_occurrences_v1","schedule",id);var pushes=retentionCount("mineagent_schedule_push_v1","schedule",id);
        return Map.of("definitionArchived",archived,"occurrences",count,"pushDeliveries",pushes,"canArchive",(TERMINAL_DEFINITIONS.contains(d.state())||wall.getAsLong()>=d.expires())&&(!archived||count.hot()>0||pushes.hot()>0),"batchLimit",256,"maximumRetainedPerTable",RetainedRows.MAX_RETAINED_ROWS,"diskReclaimed",false);
    }
    /** No Task-only Context is borrowed: the current viewer owns the management receipt. */
    public synchronized ManagementResult managementWrite(ManagementContext context,UUID operation,UUID id,long expectedRevision,String action,boolean confirmed)throws Exception{
        if(!confirmed||expectedRevision<1||!Set.of("ACTIVE","PAUSED","CANCELLED","ARCHIVE").contains(action))throw new IllegalArgumentException("SCHEDULE_MANAGEMENT_CONFIRM");Objects.requireNonNull(operation);management(context,null,false);
        return tx(()->{var d=managementInspect(context,id);String input=context.world()+"|"+context.owner()+"|"+id+"|"+expectedRevision+"|"+action;
            String fp=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));var old=read("mineagent_schedule_management_v1",operation,ManagementOperation.class);
            if(old!=null){if(!context.owner().equals(old.owner())||!fp.equals(old.fingerprint()))throw new IllegalArgumentException("SCHEDULE_OPERATION_REUSED");return new ManagementResult(old.receipt(),true,d.state(),d.revision());}
            if(read("mineagent_schedule_operations_v1",operation,Operation.class)!=null)throw new IllegalArgumentException("SCHEDULE_OPERATION_REUSED");
            if(d.revision()!=expectedRevision)throw new IllegalStateException("SCHEDULE_STALE");budget("mineagent_schedule_management_v1",8192);int definitions=0,occurrences=0,pushes=0;
            if(action.equals("ARCHIVE")){
                if(!TERMINAL_DEFINITIONS.contains(d.state())&&wall.getAsLong()<d.expires())throw new IllegalStateException("SCHEDULE_ARCHIVE_REQUIRES_STOPPED");
                definitions=archiveDefinition(d);occurrences=archiveOccurrences(id,256);pushes=archivePush(id,256);d=definition(id);
            }else{
                if(!Set.of("ACTIVE","PAUSED").contains(d.state())&&!d.state().equals(action))throw new IllegalStateException("SCHEDULE_STALE");
                if(!action.equals("CANCELLED")&&wall.getAsLong()>=d.expires())throw new IllegalStateException("SCHEDULE_EXPIRED");
                if(action.equals("ACTIVE")&&d.cursor()>=d.spec().maxOccurrences()-1)throw new IllegalStateException("SCHEDULE_EXHAUSTED");
                management(context,d,action.equals("ACTIVE"));if(!d.state().equals(action))d=transition(d,action);
            }
            var receipt=new ManagementReceipt(operation,id,action,d.state(),d.revision(),definitions,occurrences,pushes);
            try(var query=db.prepareStatement("INSERT INTO mineagent_schedule_management_v1(world,id,payload) VALUES(?,?,?)")){query.setString(1,world.toString());query.setString(2,operation.toString());query.setString(3,json.writeValueAsString(new ManagementOperation(context.owner(),fp,receipt)));query.executeUpdate();}
            management(context,d,action.equals("ACTIVE"));return new ManagementResult(receipt,false,d.state(),d.revision());
        });
    }
    public synchronized Definition definitionForRuntime(UUID id)throws Exception{return definition(id);}
    public synchronized List<Occurrence> bindings()throws Exception{return allOccurrences().stream().filter(o->o.taskId()!=null).toList();}
    public synchronized Optional<Occurrence> forTask(UUID task)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND task=?")){q.setString(1,world.toString());q.setString(2,task.toString());try(var r=q.executeQuery()){return r.next()?Optional.of(json.readValue(r.getString(1),Occurrence.class)):Optional.empty();}}}
    private Occurrence change(Occurrence o,String state,String error){return new Occurrence(o.id(),o.scheduleId(),o.index(),o.definitionRevision(),o.conditionEvent(),state,o.authority(),o.taskId(),o.modelAttempts(),o.maxModelCalls(),o.due(),o.recordedAt(),error,o.scriptResult(),o.deliveryResult());}
    private long pending(UUID id)throws Exception{try(var q=db.prepareStatement("SELECT COUNT(*) FROM mineagent_schedule_occurrences_v1 WHERE world=? AND schedule=? AND state IN ('QUEUED','CLAIMED','DISPATCHED','SCRIPT_QUEUED','SCRIPT_DISPATCHING','PUSH_QUEUED','PUSH_DISPATCHING','PUSH_WAITING','DELIVERY_QUEUED','DELIVERY_DISPATCHING')")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private void cancelPending(UUID id,String error)throws Exception{for(var o:allOccurrences())if(o.scheduleId().equals(id)&&Set.of("QUEUED","CLAIMED","DISPATCHED","SCRIPT_QUEUED","SCRIPT_DISPATCHING","PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING","DELIVERY_QUEUED","DELIVERY_DISPATCHING").contains(o.state()))writeOccurrence(change(o,o.state().startsWith("DELIVERY_")?(o.state().equals("DELIVERY_QUEUED")?"DELIVERY_CANCELLED":"DELIVERY_INTERRUPTED"):o.state().startsWith("PUSH_")?"PUSH_INTERRUPTED":o.state().equals("SCRIPT_DISPATCHING")?"SCRIPT_INTERRUPTED":o.state().equals("SCRIPT_QUEUED")?"SCRIPT_CANCELLED":"CANCELLED",error));}
    private List<Occurrence> queued()throws Exception{var values=new ArrayList<Occurrence>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_schedule_occurrences_v1 WHERE world=? AND state='QUEUED' ORDER BY rowid LIMIT 8")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())values.add(json.readValue(r.getString(1),Occurrence.class));}}return values;}
    private List<Definition> allDefinitions()throws Exception{return list("mineagent_schedules_v1",Definition.class);}
    private List<Occurrence> allOccurrences()throws Exception{return list("mineagent_schedule_occurrences_v1",Occurrence.class);}
    private <T>List<T> list(String table,Class<T> type)throws Exception{var values=new ArrayList<T>();try(var q=db.prepareStatement("SELECT payload FROM "+table+" WHERE world=? AND archived=0 ORDER BY rowid")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())values.add(json.readValue(r.getString(1),type));}}return values;}
    private Definition definition(UUID id)throws Exception{return read("mineagent_schedules_v1",id,Definition.class);}
    private Occurrence occurrence(UUID id)throws Exception{return read("mineagent_schedule_occurrences_v1",id,Occurrence.class);}
    private Operation operation(UUID id)throws Exception{if(read("mineagent_schedule_management_v1",id,ManagementOperation.class)!=null)throw new IllegalArgumentException("SCHEDULE_OPERATION_REUSED");return read("mineagent_schedule_operations_v1",id,Operation.class);}
    private <T>T read(String table,UUID id,Class<T> type)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM "+table+" WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){return r.next()?json.readValue(r.getString(1),type):null;}}}
    private void budget(String table,int limit)throws Exception{
        RetainedRows.requireCapacity(db,world,table);if(RetainedRows.usage(db,world,table).hot()>=limit){
            if(table.equals("mineagent_schedules_v1"))archiveDefinitions(null,null,128);
            else if(table.equals("mineagent_schedule_occurrences_v1"))archiveOccurrences(null,256);
            else if(table.equals("mineagent_schedule_push_v1"))archivePush(null,256);
            else{var rows=new ArrayList<Long>();try(var query=db.prepareStatement("SELECT rowid FROM "+table+" WHERE world=? AND archived=0 ORDER BY rowid LIMIT 256")){query.setString(1,world.toString());try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}RetainedRows.archive(db,world,table,rows);}
        }if(RetainedRows.usage(db,world,table).hot()>=limit)throw new IllegalStateException("SCHEDULE_STORAGE_BUDGET");
    }
    private int archiveOccurrences(UUID id,int limit)throws Exception{
        var rows=new ArrayList<Long>();try(var query=db.prepareStatement("SELECT rowid FROM mineagent_schedule_occurrences_v1 WHERE world=? AND archived=0 AND state IN ("+TERMINAL_OCCURRENCES+")"+(id==null?"":" AND schedule=?")+" ORDER BY rowid LIMIT ?")){query.setString(1,world.toString());if(id!=null)query.setString(2,id.toString());query.setInt(id==null?2:3,limit);try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}return RetainedRows.archive(db,world,"mineagent_schedule_occurrences_v1",rows);
    }
    private int archivePush(UUID schedule,int limit)throws Exception{var rows=new ArrayList<Long>();try(var query=db.prepareStatement("SELECT rowid FROM mineagent_schedule_push_v1 WHERE world=? AND archived=0 AND state IN ('READ_DELIVERED','EXPIRED','REJECTED','INTERRUPTED')"+(schedule==null?"":" AND schedule=?")+" ORDER BY rowid LIMIT ?")){query.setString(1,world.toString());if(schedule!=null)query.setString(2,schedule.toString());query.setInt(schedule==null?2:3,limit);try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}return RetainedRows.archive(db,world,"mineagent_schedule_push_v1",rows);}
    private int archiveDefinitions(UUID owner,UUID agent,int limit)throws Exception{int count=0;for(var d:allDefinitions()){if(owner!=null&&!owner.equals(d.creator().scope().ownerId())||agent!=null&&!agent.equals(d.creator().scope().agentId()))continue;count+=archiveDefinition(d);if(count>=limit)break;}return count;}
    private int archiveDefinition(Definition d)throws Exception{
        if(archivedDefinition(d.id()))return 0;
        if(!TERMINAL_DEFINITIONS.contains(d.state())){if(wall.getAsLong()<d.expires())return 0;writeDefinition(update(d,d.cursor(),d.tickHighWater(),d.conditionCursor(),d.skipped(),"EXPIRED",d.revision()+1,"EXPIRED_RETAINED"));cancelPending(d.id(),"SCHEDULE_EXPIRED");}
        if(pending(d.id())!=0)return 0;
        try(var query=db.prepareStatement("UPDATE mineagent_schedules_v1 SET archived=1 WHERE world=? AND id=? AND archived=0")){query.setString(1,world.toString());query.setString(2,d.id().toString());return query.executeUpdate();}
    }
    private void writeDefinition(Definition d)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_schedules_v1(world,id,owner,agent,state,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,payload=excluded.payload")){q.setString(1,world.toString());q.setString(2,d.id().toString());q.setString(3,d.creator().scope().ownerId().toString());q.setString(4,d.creator().scope().agentId().toString());q.setString(5,d.state());q.setString(6,json.writeValueAsString(d));q.executeUpdate();}}
    private void writeOccurrence(Occurrence o)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_schedule_occurrences_v1(world,id,schedule,slot,state,task,payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,payload=excluded.payload")){q.setString(1,world.toString());q.setString(2,o.id().toString());q.setString(3,o.scheduleId().toString());q.setLong(4,o.index());q.setString(5,o.state());q.setString(6,o.taskId()==null?null:o.taskId().toString());q.setString(7,json.writeValueAsString(o));q.executeUpdate();}}
    private void writeOperation(UUID id,Operation o)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_schedule_operations_v1(world,id,payload) VALUES(?,?,?)")){q.setString(1,world.toString());q.setString(2,id.toString());q.setString(3,json.writeValueAsString(o));q.executeUpdate();}}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T tx(Work<T> work)throws Exception{try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{T value=work.run();s.execute("COMMIT");return value;}catch(Exception|Error e){try{s.execute("ROLLBACK");}catch(Exception rollback){e.addSuppressed(rollback);}throw e;}}}
    @Override public synchronized void close()throws Exception{try{db.close();}finally{try{lock.close();}finally{channel.close();}}}
}
