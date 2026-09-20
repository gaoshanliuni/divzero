package dev.mineagent.runtime.core.events;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.persistence.RetainedRows;
import dev.mineagent.runtime.api.directory.ObjectRef;
import dev.mineagent.runtime.core.directory.*;
import dev.mineagent.runtime.core.shared.*;
import dev.mineagent.runtime.core.feedback.UiFeedbackStore;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Durable Native event consumption and bounded task outbox. No model or arbitrary Native effect executes inside SQL. */
public final class RuntimeEventStore implements AutoCloseable {
    public record Context(ObjectDirectory.Scope scope,UUID serverEpoch,long eventAuthority,UUID operation,long sharedAuthority){public Context(ObjectDirectory.Scope scope,UUID epoch,long authority,UUID operation){this(scope,epoch,authority,operation,-1);}
        public Context{Objects.requireNonNull(scope);Objects.requireNonNull(serverEpoch);Objects.requireNonNull(operation);if(eventAuthority<0)throw new IllegalArgumentException("EVENT_CONTEXT");}}
    public record Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds,SharedSubscriptionFilter shared,dev.mineagent.runtime.core.feedback.FeedbackSubscriptionFilter feedback,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ObjectInteractionFilter object,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ScoreEventFilter score,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ScriptEventConsumer script,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) StatePushConsumer push){
        public Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds,SharedSubscriptionFilter shared,dev.mineagent.runtime.core.feedback.FeedbackSubscriptionFilter feedback,ObjectInteractionFilter object,ScoreEventFilter score,ScriptEventConsumer script){this(sources,mode,query,goal,maxWakes,maxModelCalls,pendingLimit,cooldownMillis,ttlSeconds,shared,feedback,object,score,script,null);}
        public Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds,SharedSubscriptionFilter shared,dev.mineagent.runtime.core.feedback.FeedbackSubscriptionFilter feedback,ObjectInteractionFilter object,ScoreEventFilter score){this(sources,mode,query,goal,maxWakes,maxModelCalls,pendingLimit,cooldownMillis,ttlSeconds,shared,feedback,object,score,null);}
        public Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds,SharedSubscriptionFilter shared,dev.mineagent.runtime.core.feedback.FeedbackSubscriptionFilter feedback,ObjectInteractionFilter object){this(sources,mode,query,goal,maxWakes,maxModelCalls,pendingLimit,cooldownMillis,ttlSeconds,shared,feedback,object,null);}
        public Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds,SharedSubscriptionFilter shared,dev.mineagent.runtime.core.feedback.FeedbackSubscriptionFilter feedback){this(sources,mode,query,goal,maxWakes,maxModelCalls,pendingLimit,cooldownMillis,ttlSeconds,shared,feedback,null);}
        public Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds){this(sources,mode,query,goal,maxWakes,maxModelCalls,pendingLimit,cooldownMillis,ttlSeconds,null,null);}
        public Request(Set<String> sources,String mode,ObjectQuery query,String goal,int maxWakes,int maxModelCalls,int pendingLimit,long cooldownMillis,int ttlSeconds,SharedSubscriptionFilter shared){this(sources,mode,query,goal,maxWakes,maxModelCalls,pendingLimit,cooldownMillis,ttlSeconds,shared,null);}
        public Request{sources=Set.copyOf(sources);if(sources.isEmpty()||(score!=null?(!sources.equals(Set.of(ScoreEventFilter.SOURCE))||query!=null||shared!=null||feedback!=null||object!=null):object!=null?(!sources.equals(Set.of(ObjectInteractionFilter.SOURCE))||shared!=null||feedback!=null||query!=null):feedback!=null?(!sources.equals(Set.of("UI_FEEDBACK"))||shared!=null||query!=null):(shared==null?(!Set.of("PLAYER_JOIN","PLAYER_LEAVE","PLAYER_REGION_ENTER","PLAYER_REGION_LEAVE").containsAll(sources)||query==null||query.kind()!=ObjectRef.Kind.PLAYER||!query.cursor().isEmpty()):(!sources.equals(Set.of("SHARED_STATE_CHANGED"))||query!=null)))||!Set.of("RECORD_ONLY","AGENT_WAKE","SCRIPT","STATE_PUSH").contains(mode)||goal==null||goal.codePointCount(0,goal.length())>200||pendingLimit<1||pendingLimit>8||cooldownMillis<0||cooldownMillis>600000||ttlSeconds<1||ttlSeconds>604800)throw new IllegalArgumentException("EVENT_SUBSCRIPTION_ARGUMENTS");
            if(!Collections.disjoint(sources,PlayerRegionEvents.SOURCES)&&(!PlayerRegionEvents.SOURCES.containsAll(sources)||!PlayerRegionEvents.validQuery(query)))throw new IllegalArgumentException("REGION_FIXED_QUERY_REQUIRED");
            if(mode.equals("SCRIPT")!=(script!=null)||mode.equals("STATE_PUSH")!=(push!=null)||script!=null&&push!=null||(script!=null||push!=null)&&feedback!=null)throw new IllegalArgumentException("SCRIPT_CONSUMER_MODE");
            if(!mode.equals("AGENT_WAKE")?(!goal.isEmpty()||maxWakes!=0||maxModelCalls!=0):(goal.isBlank()||maxWakes<1||maxWakes>32||maxModelCalls<1||maxModelCalls>16))throw new IllegalArgumentException("EVENT_WAKE_BUDGET_REQUIRED");}
        public String canonical(){try{var data=new TreeMap<String,Object>();data.put("sources",sources.stream().sorted().toList());data.put("mode",mode);if(script!=null)data.put("script",script.wire());if(push!=null)data.put("push",push.wire());if(score!=null)data.put("score",score.wire());else if(object!=null)data.put("object",object.wire());else if(feedback!=null)data.put("feedback",feedback.wire());else if(shared==null)data.put("query",query.canonical());else data.put("shared",shared.wire());data.put("goal",goal);data.put("maxWakes",maxWakes);data.put("maxModelCalls",maxModelCalls);data.put("pendingLimit",pendingLimit);data.put("cooldownMillis",cooldownMillis);data.put("ttlSeconds",ttlSeconds);return new ObjectMapper().writeValueAsString(data);}catch(Exception e){throw new IllegalArgumentException("EVENT_REQUEST_ENCODING",e);}}
    }
    public record SharedChange(SharedStateTarget target,long revision,boolean schema,String actorKind,List<SharedStateStore.Change> changes,SharedStateStore.Provenance origin){
        public SharedChange{Objects.requireNonNull(target);changes=List.copyOf(changes);origin=origin==null?SharedStateStore.Provenance.NONE:origin;if(target.namespace().isEmpty()||revision<1||changes.size()>64||!Set.of("PLAYER","AGENT","PACKAGE","FEEDBACK","SYSTEM_EXPIRY").contains(actorKind))throw new IllegalArgumentException("SHARED_EVENT_DATA");}
    }
    /** Immutable routing metadata only; payload/result values remain in the authorized feedback store. */
    public record FeedbackChange(UUID feedbackId,UiFeedbackStore.Scope scope,UiFeedbackStore.Document document,long sequence){
        public FeedbackChange(UUID feedbackId,UiFeedbackStore.Scope scope,UiFeedbackStore.Document document){this(feedbackId,scope,document,0);}
        public FeedbackChange{Objects.requireNonNull(feedbackId);Objects.requireNonNull(scope);Objects.requireNonNull(document);}
    }
    public record Event(UUID id,UUID world,UUID sourceEpoch,String source,UUID author,ObjectDirectory.Entry subject,long occurredAt,UUID causation,List<UUID> subscriptionChain,SharedChange shared,FeedbackChange feedback,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) PlayerRegionEvents.Change region,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ObjectInteractionFilter.Change object,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ScoreEventFilter.Change score){
        public Event(UUID id,UUID world,UUID epoch,String source,UUID author,ObjectDirectory.Entry subject,long at,UUID causation,List<UUID> chain,SharedChange shared,FeedbackChange feedback,PlayerRegionEvents.Change region,ObjectInteractionFilter.Change object){this(id,world,epoch,source,author,subject,at,causation,chain,shared,feedback,region,object,null);}
        public Event(UUID id,UUID world,UUID epoch,String source,UUID author,ObjectDirectory.Entry subject,long at,UUID causation,List<UUID> chain,SharedChange shared,FeedbackChange feedback,PlayerRegionEvents.Change region){this(id,world,epoch,source,author,subject,at,causation,chain,shared,feedback,region,null);}
        public Event(UUID id,UUID world,UUID epoch,String source,UUID author,ObjectDirectory.Entry subject,long at,UUID causation,List<UUID> chain,SharedChange shared,FeedbackChange feedback){this(id,world,epoch,source,author,subject,at,causation,chain,shared,feedback,null);}
        public Event(UUID id,UUID world,UUID epoch,String source,UUID author,ObjectDirectory.Entry subject,long at,UUID causation,List<UUID> chain){this(id,world,epoch,source,author,subject,at,causation,chain,null,null);}
        public Event(UUID id,UUID world,UUID epoch,String source,UUID author,ObjectDirectory.Entry subject,long at,UUID causation,List<UUID> chain,SharedChange shared){this(id,world,epoch,source,author,subject,at,causation,chain,shared,null);}
        public Event{Objects.requireNonNull(id);Objects.requireNonNull(world);Objects.requireNonNull(sourceEpoch);Objects.requireNonNull(author);subscriptionChain=List.copyOf(subscriptionChain);
            boolean invalid=score!=null?(!ScoreEventFilter.SOURCE.equals(source)||region!=null||object!=null||shared!=null||feedback!=null||subject!=null||causation!=null||!subscriptionChain.isEmpty()||!ScoreEventFilter.systemAuthor(world).equals(author)||occurredAt!=score.observedAfter()):object!=null?(!ObjectInteractionFilter.SOURCE.equals(source)||region!=null||feedback!=null||shared!=null||subject==null||subject.ref().kind()!=ObjectRef.Kind.PLAYER||!world.equals(subject.ref().worldId())||!author.toString().equals(subject.ref().id())||!subject.dimension().equals(object.objectPosition().dimension())||!Objects.equals(causation,object.origin().taskId())||!subscriptionChain.equals(object.origin().subscriptionChain())):region!=null?(feedback!=null||shared!=null||subject==null||!world.equals(region.before().ref().worldId())||!world.equals(subject.ref().worldId())||!author.toString().equals(subject.ref().id())||occurredAt!=region.observedAfter()||causation!=null||!subscriptionChain.isEmpty()):feedback!=null?(!source.equals("UI_FEEDBACK")||shared!=null||subject!=null||!id.equals(feedback.feedbackId())||!world.equals(feedback.scope().worldId())||!author.equals(feedback.scope().authorId())||!Objects.equals(causation,feedback.scope().causation())||!subscriptionChain.equals(feedback.scope().causalChain()))
                    :shared==null?(!Set.of("PLAYER_JOIN","PLAYER_LEAVE").contains(source)||subject==null||subject.ref().kind()!=ObjectRef.Kind.PLAYER||!world.equals(subject.ref().worldId())||!author.toString().equals(subject.ref().id())):(!source.equals("SHARED_STATE_CHANGED")||subject!=null);
            if(object!=null)ObjectInteractionFilter.validate(object,subject);
            if(region!=null)PlayerRegionEvents.validate(region,subject,source);
            if(invalid||occurredAt<0||subscriptionChain.size()>8||new HashSet<>(subscriptionChain).size()!=subscriptionChain.size())throw new IllegalArgumentException("RUNTIME_EVENT_INVALID");}
        public static Event feedback(UiFeedbackStore.Item accepted){
            if(!accepted.state().equals("ACCEPTED")||accepted.exported())throw new IllegalArgumentException("FEEDBACK_EVENT_NOT_PENDING");
            var scope=accepted.scope();var epoch=UUID.nameUUIDFromBytes((scope.worldId()+"|ui-feedback-v1").getBytes(StandardCharsets.UTF_8));
            return new Event(accepted.id(),scope.worldId(),epoch,"UI_FEEDBACK",scope.authorId(),null,accepted.createdAt(),scope.causation(),scope.causalChain(),null,new FeedbackChange(accepted.id(),scope,accepted.document(),accepted.sequence()));
        }
    }
    public record Subscription(UUID id,Context creator,Request request,String state,long revision,long cursor,int wakesReserved,long lastWakeAt,long expiresAt,String error){}
    public record Trigger(UUID id,UUID subscriptionId,Event event,long subscriptionRevision,String state,String authority,UUID taskId,int modelAttempts,int maxModelCalls,long createdAt,String error,Map<String,String> matchObservation,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String scriptResult){
        public Trigger(UUID id,UUID subscription,Event event,long revision,String state,String authority,UUID task,int attempts,int maximum,long at,String error,Map<String,String> observation){this(id,subscription,event,revision,state,authority,task,attempts,maximum,at,error,observation,"");}
        public Trigger(UUID id,UUID subscription,Event event,long revision,String state,String authority,UUID task,int attempts,int maximum,long at,String error){this(id,subscription,event,revision,state,authority,task,attempts,maximum,at,error,Map.of());}
        public Trigger{scriptResult=scriptResult==null?"":scriptResult;if(!scriptResult.isEmpty())scriptResult=ScriptEventConsumer.normalizeResult(scriptResult);matchObservation=matchObservation==null?Map.of():Map.copyOf(matchObservation);if(matchObservation.size()>8||matchObservation.toString().length()>2048)throw new IllegalArgumentException("EVENT_MATCH_METADATA_BUDGET");}
    }
    public record PushDelivery(UUID id,UUID triggerId,UUID subscriptionId,dev.mineagent.runtime.api.ui.UiProtocol.Session session,String state,long createdAt,long expiresAt,int attempts,long lastAttempt,long readRevision,String error){}
    public record ManagementContext(UUID world,UUID owner){public ManagementContext{Objects.requireNonNull(world);Objects.requireNonNull(owner);}}
    public record ManagementPage(int total,int offset,int nextOffset,boolean more,List<Subscription> subscriptions){public ManagementPage{subscriptions=List.copyOf(subscriptions);}}
    public record ArchiveCounts(int subscriptions,int triggers,int pushDeliveries){}
    public record RetentionCount(long hot,long archived,long total){}
    public record ManagementReceipt(UUID operation,UUID subscription,String state,long revision,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ArchiveCounts archive){
        public ManagementReceipt(UUID operation,UUID subscription,String state,long revision){this(operation,subscription,state,revision,null);}
    }
    public record ManagementResult(ManagementReceipt receipt,boolean duplicate,String currentState,long currentRevision){}
    private record ManagementOperation(UUID owner,String fingerprint,ManagementReceipt receipt){}
    private record Operation(Context context,String fingerprint,String result){}
    public interface Port {void authorize(Context context);String authority(Subscription subscription);boolean matches(Subscription subscription,Event event);
        default void authorizeRequest(Context context,Request request){authorize(context);}
        default Request bindAtCreation(Context context,Request request){return request;}
        default Map<String,String> matchObservation(Subscription subscription,Event event){return Map.of();}
        /** Prepare value observations before this store takes the shared SQLite writer lock. */
        default void prepareEvent(Event event)throws Exception{}
        /** A valid standing subscription can wait for a restorable source; this is never a task execution grant. */
        default boolean waitingForSource(Subscription subscription){return false;}
        default void authorizeManagement(ManagementContext context,Subscription subscription,boolean activate){throw new SecurityException("EVENT_MANAGEMENT_UNAVAILABLE");}
        default Request bindManagementResume(ManagementContext context,Subscription subscription){return subscription.request();}
    }
    private static final List<String> RETAINED_TABLES=List.of("mineagent_events_v1","mineagent_subscriptions_v1","mineagent_event_triggers_v1","mineagent_event_operations_v1","mineagent_event_management_v1","mineagent_event_push_v1");
    private static final String TERMINAL_TRIGGERS="'RECORDED','COMPLETED','CANCELLED','FAILED','INTERRUPTED','MODEL_BUDGET_EXHAUSTED','CYCLE_REJECTED','THROTTLED','BACKPRESSURE','BUDGET_EXHAUSTED','SCRIPT_HANDLED','SCRIPT_CANCELLED','SCRIPT_INTERRUPTED','PUSH_READ_DELIVERED','PUSH_NO_RECIPIENTS','PUSH_PARTIAL_OR_FAILED','PUSH_INTERRUPTED'";
    private final Connection db;private final UUID world;private final LongSupplier now;private final Port port;private final FileChannel channel;private final FileLock lock;private final ObjectMapper json=new ObjectMapper();
    private RuntimeEventStore(Connection db,UUID world,LongSupplier now,Port port,FileChannel channel,FileLock lock)throws Exception{
        this.db=db;this.world=world;this.now=now;this.port=port;this.channel=channel;this.lock=lock;
        try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE IF NOT EXISTS mineagent_events_v1(world TEXT NOT NULL,id TEXT NOT NULL,sequence INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id),UNIQUE(world,sequence))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_subscriptions_v1(world TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_event_triggers_v1(world TEXT NOT NULL,id TEXT NOT NULL,subscription TEXT NOT NULL,event_id TEXT NOT NULL,state TEXT NOT NULL,task_id TEXT,payload TEXT NOT NULL,PRIMARY KEY(world,id),UNIQUE(world,subscription,event_id),UNIQUE(world,task_id))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_event_operations_v1(world TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE TABLE IF NOT EXISTS mineagent_event_push_v1(world TEXT NOT NULL,id TEXT NOT NULL,trigger_id TEXT NOT NULL,state TEXT NOT NULL,last_attempt INTEGER NOT NULL,expires_at INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE INDEX IF NOT EXISTS mineagent_event_push_queue_v1 ON mineagent_event_push_v1(world,state,last_attempt)");s.execute("CREATE TABLE IF NOT EXISTS mineagent_event_management_v1(world TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE INDEX IF NOT EXISTS mineagent_event_queue_v1 ON mineagent_event_triggers_v1(world,state)");s.execute("CREATE INDEX IF NOT EXISTS mineagent_event_subscription_queue_v1 ON mineagent_event_triggers_v1(world,subscription,state)");}
        tx(()->{RetainedRows.initialize(db,world,RETAINED_TABLES);boolean mode=false;try(var query=db.createStatement();var rows=query.executeQuery("PRAGMA table_info(mineagent_subscriptions_v1)")){while(rows.next())if(rows.getString("name").equals("mode"))mode=true;}
            if(!mode)try(var update=db.createStatement()){update.execute("ALTER TABLE mineagent_subscriptions_v1 ADD COLUMN mode TEXT NOT NULL DEFAULT ''");}
            // Backfill this world even if another world's opener added the shared schema column first.
            var modes=new LinkedHashMap<String,String>();try(var query=db.prepareStatement("SELECT id,payload FROM mineagent_subscriptions_v1 WHERE world=? AND mode=''")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())modes.put(rows.getString(1),json.readValue(rows.getString(2),Subscription.class).request().mode());}}
            try(var update=db.prepareStatement("UPDATE mineagent_subscriptions_v1 SET mode=? WHERE world=? AND id=?")){for(var entry:modes.entrySet()){update.setString(1,entry.getValue());update.setString(2,world.toString());update.setString(3,entry.getKey());update.executeUpdate();}}
            try(var update=db.createStatement()){update.execute("CREATE INDEX IF NOT EXISTS mineagent_subscriptions_owner_mode_v1 ON mineagent_subscriptions_v1(world,owner,mode,state)");update.execute("CREATE INDEX IF NOT EXISTS mineagent_trigger_history_v1 ON mineagent_event_triggers_v1(world,subscription)");update.execute("CREATE INDEX IF NOT EXISTS mineagent_trigger_task_v1 ON mineagent_event_triggers_v1(world,task_id)");}
            try(var update=db.createStatement()){
                update.execute("CREATE INDEX IF NOT EXISTS mineagent_trigger_event_v1 ON mineagent_event_triggers_v1(world,event_id)");
                update.execute("CREATE INDEX IF NOT EXISTS mineagent_trigger_retention_v1 ON mineagent_event_triggers_v1(world,subscription,archived,state)");
                update.execute("CREATE INDEX IF NOT EXISTS mineagent_push_retention_v1 ON mineagent_event_push_v1(world,trigger_id,archived,state)");
                update.execute("CREATE INDEX IF NOT EXISTS mineagent_owner_retention_v1 ON mineagent_subscriptions_v1(world,owner,archived)");
            }
            return null;});
        tx(()->{for(var t:allTriggers())if(Set.of("QUEUED","CLAIMED","DISPATCHED").contains(t.state()))writeTrigger(change(t,"INTERRUPTED","SERVER_RESTART_NO_REPLAY"));else if(Set.of("SCRIPT_QUEUED","SCRIPT_DISPATCHING").contains(t.state()))writeTrigger(change(t,"SCRIPT_INTERRUPTED","SERVER_RESTART_NO_SCRIPT_REPLAY"));else if(t.state().startsWith("PUSH_")&&Set.of("PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING").contains(t.state()))writeTrigger(change(t,"PUSH_INTERRUPTED","SERVER_RESTART_NO_PUSH_REPLAY"));return null;});
    }
    public static RuntimeEventStore open(Path path,UUID world,LongSupplier now,Port port)throws Exception{
        Objects.requireNonNull(world);Objects.requireNonNull(now);Objects.requireNonNull(port);Path p=path.toAbsolutePath().normalize();Files.createDirectories(p.getParent());var channel=FileChannel.open(p.resolveSibling(p.getFileName()+".events-"+world+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;Connection db=null;
        try{try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IllegalStateException("EVENT_STORE_ALREADY_OPEN",busy);}if(lock==null)throw new IllegalStateException("EVENT_STORE_ALREADY_OPEN");db=DriverManager.getConnection("jdbc:sqlite:"+p);return new RuntimeEventStore(db,world,now,port,channel,lock);}catch(Exception e){if(db!=null)try{db.close();}catch(Exception x){e.addSuppressed(x);}if(lock!=null)try{lock.close();}catch(Exception x){e.addSuppressed(x);}try{channel.close();}catch(Exception x){e.addSuppressed(x);}throw e;}
    }
    private void authorize(Context c){if(!world.equals(c.scope().worldId()))throw new SecurityException("EVENT_WORLD_MISMATCH");port.authorize(c);}
    private String fingerprint(Context c,String kind,String request)throws Exception{var s=c.scope();return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((s.worldId()+"|"+s.ownerId()+"|"+s.agentId()+"|"+s.taskId()+"|"+s.intentRevision()+"|"+kind+"|"+request).getBytes(StandardCharsets.UTF_8)));}
    private Subscription owned(Context c,UUID id)throws Exception{var value=subscription(id);if(value==null||!value.creator().scope().ownerId().equals(c.scope().ownerId())||!value.creator().scope().agentId().equals(c.scope().agentId()))throw new SecurityException("EVENT_SUBSCRIPTION_NOT_OWNED");return value;}
    public synchronized Subscription subscribe(Context c,Request input)throws Exception{
        authorize(c);return tx(()->{port.authorizeRequest(c,input);Request request=port.bindAtCreation(c,input);String fp=fingerprint(c,"SUBSCRIBE",request.canonical());var old=operation(c.operation());if(old!=null){if(!fp.equals(old.fingerprint()))throw new IllegalArgumentException("EVENT_OPERATION_REUSED");return owned(c,UUID.fromString(old.result()));}
            budget("mineagent_event_operations_v1",8192);budget("mineagent_subscriptions_v1",128);long count=allSubscriptions().stream().filter(s->s.creator().scope().ownerId().equals(c.scope().ownerId())&&s.creator().scope().agentId().equals(c.scope().agentId())).count();if(count>=32){archiveSubscriptions(c.scope().ownerId(),c.scope().agentId(),128);count=allSubscriptions().stream().filter(s->s.creator().scope().ownerId().equals(c.scope().ownerId())&&s.creator().scope().agentId().equals(c.scope().agentId())).count();if(count>=32)throw new IllegalStateException("EVENT_OWNER_BUDGET");}
            if(request.feedback()!=null&&request.mode().equals("AGENT_WAKE")&&allSubscriptions().stream().anyMatch(s->s.state().equals("ACTIVE")&&now.getAsLong()<s.expiresAt()&&s.request().mode().equals("AGENT_WAKE")&&s.creator().scope().ownerId().equals(c.scope().ownerId())&&s.creator().scope().agentId().equals(c.scope().agentId())&&request.feedback().overlaps(s.request().feedback())))throw new IllegalStateException("FEEDBACK_CONSUMER_ALREADY_BOUND");
            UUID id=stable("subscription",c.operation());var sub=new Subscription(id,c,request,"ACTIVE",1,head(),0,0,Math.addExact(now.getAsLong(),request.ttlSeconds()*1000L),"");writeSubscription(sub);writeOperation(c.operation(),new Operation(c,fp,id.toString()));port.authorizeRequest(c,request);return sub;});
    }
    public synchronized Subscription state(Context c,UUID id,long expected,String state)throws Exception{
        authorize(c);if(!Set.of("ACTIVE","PAUSED","CANCELLED").contains(state))throw new IllegalArgumentException("EVENT_STATE_INVALID");return tx(()->{authorize(c);var sub=owned(c,id);port.authorizeRequest(c,sub.request());String fp=fingerprint(c,"STATE",id+"|"+expected+"|"+state);var old=operation(c.operation());if(old!=null){if(!old.fingerprint().equals(fp))throw new IllegalArgumentException("EVENT_OPERATION_REUSED");return sub;}
            if(sub.revision()!=expected||sub.state().equals("CANCELLED")||now.getAsLong()>=sub.expiresAt())throw new IllegalStateException("EVENT_SUBSCRIPTION_STALE");budget("mineagent_event_operations_v1",8192);
            if(state.equals("ACTIVE")&&sub.request().mode().equals("AGENT_WAKE")&&sub.wakesReserved()>=sub.request().maxWakes())throw new IllegalStateException("EVENT_WAKE_BUDGET");if(state.equals("ACTIVE")&&sub.request().script()!=null&&scriptRuns(sub.id())>=sub.request().script().maxRuns())throw new IllegalStateException("SCRIPT_INVOCATION_BUDGET");if(state.equals("ACTIVE")&&sub.request().push()!=null&&pushSignals(sub.id())>=sub.request().push().maxSignals())throw new IllegalStateException("STATE_PUSH_SIGNAL_BUDGET");var resumedRequest=state.equals("ACTIVE")?port.bindAtCreation(c,sub.request()):sub.request();if(state.equals("ACTIVE")&&resumedRequest.feedback()!=null&&resumedRequest.mode().equals("AGENT_WAKE")&&allSubscriptions().stream().anyMatch(other->!other.id().equals(id)&&other.state().equals("ACTIVE")&&now.getAsLong()<other.expiresAt()&&other.request().mode().equals("AGENT_WAKE")&&other.creator().scope().ownerId().equals(c.scope().ownerId())&&other.creator().scope().agentId().equals(c.scope().agentId())&&resumedRequest.feedback().overlaps(other.request().feedback())))throw new IllegalStateException("FEEDBACK_CONSUMER_ALREADY_BOUND");var next=new Subscription(id,sub.creator(),resumedRequest,state,sub.revision()+1,head(),sub.wakesReserved(),sub.lastWakeAt(),sub.expiresAt(),"");writeSubscription(next);cancelPending(id,"SUBSCRIPTION_CHANGED");writeOperation(c.operation(),new Operation(c,fp,id.toString()));port.authorizeRequest(c,resumedRequest);return next;});
    }
    public synchronized long ingest(Event event)throws Exception{
        if(!world.equals(event.world()))throw new IllegalArgumentException("EVENT_WORLD_MISMATCH");port.prepareEvent(event);return tx(()->{
            try(var q=db.prepareStatement("SELECT sequence,payload FROM mineagent_events_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,event.id().toString());try(var r=q.executeQuery()){if(r.next()){if(!json.readValue(r.getString(2),Event.class).equals(event))throw new IllegalArgumentException("EVENT_ID_REUSED");return r.getLong(1);}}}
            if(event.region()!=null){var target=subscription(event.region().subscriptionId());if(target==null||!target.state().equals("ACTIVE")||!PlayerRegionEvents.matches(target,event)||port.authority(target)==null||now.getAsLong()>=target.expiresAt())throw new SecurityException("REGION_SOURCE_REVOKED");}
            budget("mineagent_events_v1",8192);long sequence=head()+1;try(var q=db.prepareStatement("INSERT INTO mineagent_events_v1(world,id,sequence,payload) VALUES(?,?,?,?)")){q.setString(1,world.toString());q.setString(2,event.id().toString());q.setLong(3,sequence);q.setString(4,json.writeValueAsString(event));q.executeUpdate();}
            for(var original:allSubscriptions()){
                if(event.score()!=null&&!ScoreEventFilter.matches(original,event))continue;
                if(event.region()!=null&&!event.region().subscriptionId().equals(original.id()))continue;
                if(event.object()!=null&&!ObjectInteractionFilter.matches(original,event))continue;
                if(!original.state().equals("ACTIVE")||original.cursor()>=sequence)continue;
                String authority;try{authority=port.authority(original);}catch(RuntimeException failure){pause(original,"AUTHORITY_CHECK_FAILED",sequence);continue;}if(authority==null&&now.getAsLong()<original.expiresAt()&&port.waitingForSource(original)){writeSubscription(new Subscription(original.id(),original.creator(),original.request(),original.state(),original.revision(),sequence,original.wakesReserved(),original.lastWakeAt(),original.expiresAt(),original.error()));continue;}if(authority==null||now.getAsLong()>=original.expiresAt()){pause(original,authority==null?"AUTHORITY_REVOKED":"EXPIRED",sequence);continue;}
                var sub=new Subscription(original.id(),original.creator(),original.request(),original.state(),original.revision(),sequence,original.wakesReserved(),original.lastWakeAt(),original.expiresAt(),original.error());writeSubscription(sub);
                if(!sub.request().sources().contains(event.source()))continue;
                try{if(!port.matches(sub,event))continue;}catch(RuntimeException failure){pause(sub,"EVENT_FILTER_UNAVAILABLE",sequence);continue;}
                Map<String,String> observed;try{observed=port.matchObservation(sub,event);}catch(RuntimeException failure){pause(sub,"EVENT_MATCH_OBSERVATION_UNAVAILABLE",sequence);continue;}budget("mineagent_event_triggers_v1",8192);String state="RECORDED",error="";UUID task=null;int reserved=sub.wakesReserved();long last=sub.lastWakeAt();
                if(event.subscriptionChain().contains(sub.id())||event.subscriptionChain().size()>=4){state="CYCLE_REJECTED";error="CAUSAL_DEPTH_OR_LOOP";}
                else if(sub.request().mode().equals("AGENT_WAKE")){
                    long pending=pendingCount(sub.id());
                    if(reserved>=sub.request().maxWakes()){state="BUDGET_EXHAUSTED";error="EVENT_WAKE_BUDGET";if(pending==0)pause(sub,error,sequence);}
                    else if(pending>=sub.request().pendingLimit()){state="BACKPRESSURE";error="EVENT_QUEUE_BUDGET";}
                    else if(last>0&&now.getAsLong()<last+sub.request().cooldownMillis()){state="THROTTLED";error="EVENT_COOLDOWN";}
                    else{state="QUEUED";reserved++;last=now.getAsLong();}
                }
                else if(sub.request().mode().equals("SCRIPT")){
                    if(scriptRuns(sub.id())>=sub.request().script().maxRuns()){state="BUDGET_EXHAUSTED";error="SCRIPT_INVOCATION_BUDGET";if(scriptPending(sub.id())==0)pause(sub,error,sequence);}
                    else if(scriptPending(sub.id())>=sub.request().pendingLimit()){state="BACKPRESSURE";error="SCRIPT_QUEUE_BUDGET";}
                    else if(last>0&&now.getAsLong()<last+sub.request().cooldownMillis()){state="THROTTLED";error="EVENT_COOLDOWN";}
                    else{state="SCRIPT_QUEUED";last=now.getAsLong();}
                }
                else if(sub.request().mode().equals("STATE_PUSH")){
                    if(pushSignals(sub.id())>=sub.request().push().maxSignals()){state="BUDGET_EXHAUSTED";error="STATE_PUSH_SIGNAL_BUDGET";if(pushPending(sub.id())==0)pause(sub,error,sequence);}
                    else if(pushPending(sub.id())>=sub.request().pendingLimit()){state="BACKPRESSURE";error="STATE_PUSH_QUEUE_BUDGET";}
                    else if(last>0&&now.getAsLong()<last+sub.request().cooldownMillis()){state="THROTTLED";error="EVENT_COOLDOWN";}
                    else{state="PUSH_QUEUED";last=now.getAsLong();}
                }
                UUID trigger=UUID.nameUUIDFromBytes((world+"|event-trigger|"+sub.id()+"|"+event.id()).getBytes(StandardCharsets.UTF_8));if(state.equals("QUEUED"))task=taskId(sub.creator().scope().ownerId(),trigger);
                writeTrigger(new Trigger(trigger,sub.id(),event,sub.revision(),state,authority,task,0,sub.request().maxModelCalls(),now.getAsLong(),error,observed));
                if(state.equals("QUEUED")||state.equals("SCRIPT_QUEUED")||state.equals("PUSH_QUEUED"))writeSubscription(new Subscription(sub.id(),sub.creator(),sub.request(),sub.state(),sub.revision(),sequence,reserved,last,sub.expiresAt(),sub.error()));
            }return sequence;
        });
    }
    private long scriptCount(UUID subscription,String condition)throws Exception{try(var query=db.prepareStatement("SELECT COUNT(*) FROM mineagent_event_triggers_v1 WHERE world=? AND subscription=? AND "+condition)){query.setString(1,world.toString());query.setString(2,subscription.toString());try(var rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}}}
    public synchronized long scriptRuns(UUID subscription)throws Exception{return scriptCount(subscription,"state GLOB 'SCRIPT_*'");}
    private long scriptPending(UUID subscription)throws Exception{return scriptCount(subscription,"state IN ('SCRIPT_QUEUED','SCRIPT_DISPATCHING')");}
    public synchronized boolean waitingForSourceForRuntime(Subscription subscription){return port.waitingForSource(subscription);}
    public synchronized Optional<Trigger> claimScript()throws Exception{
        return tx(()->{var orphaned=new ArrayList<Trigger>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND state='SCRIPT_DISPATCHING' ORDER BY rowid LIMIT 8")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())orphaned.add(json.readValue(rows.getString(1),Trigger.class));}}
            for(var orphan:orphaned){writeTrigger(change(orphan,"SCRIPT_INTERRUPTED","SCRIPT_DISPATCH_OUTCOME_UNKNOWN"));pauseExhaustedScript(orphan.subscriptionId());}
            var queued=new ArrayList<Trigger>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND state='SCRIPT_QUEUED' ORDER BY rowid LIMIT 8")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())queued.add(json.readValue(rows.getString(1),Trigger.class));}}
            for(var trigger:queued){var sub=subscription(trigger.subscriptionId());if(sub==null||sub.request().script()==null||!valid(trigger)){writeTrigger(change(trigger,"SCRIPT_CANCELLED","SCRIPT_AUTHORITY_CHANGED"));pauseExhaustedScript(trigger.subscriptionId());continue;}
                var started=change(trigger,"SCRIPT_DISPATCHING","");writeTrigger(started);return Optional.of(started);}
            return Optional.empty();
        });
    }
    private void pauseExhaustedScript(UUID id)throws Exception{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.request().script()!=null&&scriptPending(id)==0&&scriptRuns(id)>=sub.request().script().maxRuns())pause(sub,"SCRIPT_INVOCATION_BUDGET",head());}
    public synchronized boolean permitScript(UUID id)throws Exception{var trigger=trigger(id);return trigger!=null&&trigger.state().equals("SCRIPT_DISPATCHING")&&valid(trigger);}
    public synchronized void finishScript(UUID id,String result,String error)throws Exception{
        if(!Set.of("","SCRIPT_HANDLER_FAILED","SCRIPT_COMPLETION_REQUIRED","SCRIPT_DISPATCH_UNCERTAIN").contains(error))throw new IllegalArgumentException("SCRIPT_COMPLETION_CODE");String normalized=error.isEmpty()?ScriptEventConsumer.normalizeResult(result):"";
        tx(()->{var trigger=trigger(id);if(trigger==null||!trigger.state().equals("SCRIPT_DISPATCHING"))return null;
            if(!valid(trigger)){writeTrigger(change(trigger,"SCRIPT_INTERRUPTED",error.isEmpty()?"SCRIPT_AUTHORITY_CHANGED":error));pauseExhaustedScript(trigger.subscriptionId());return null;}
            writeTrigger(new Trigger(trigger.id(),trigger.subscriptionId(),trigger.event(),trigger.subscriptionRevision(),error.isEmpty()?"SCRIPT_HANDLED":"SCRIPT_INTERRUPTED",trigger.authority(),null,0,0,trigger.createdAt(),error,trigger.matchObservation(),normalized));pauseExhaustedScript(trigger.subscriptionId());return null;
        });
    }
    public synchronized long pushSignals(UUID subscription)throws Exception{return scriptCount(subscription,"state GLOB 'PUSH_*'");}
    private long pushPending(UUID subscription)throws Exception{return scriptCount(subscription,"state IN ('PUSH_QUEUED','PUSH_DISPATCHING','PUSH_WAITING')");}
    private void pauseExhaustedPush(UUID id)throws Exception{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.request().push()!=null&&pushPending(id)==0&&pushSignals(id)>=sub.request().push().maxSignals())pause(sub,"STATE_PUSH_SIGNAL_BUDGET",head());}
    public synchronized Optional<Trigger> claimPush()throws Exception{return tx(()->{
        var candidates=new ArrayList<Trigger>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND state IN ('PUSH_QUEUED','PUSH_DISPATCHING') ORDER BY rowid LIMIT 8")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())candidates.add(json.readValue(rows.getString(1),Trigger.class));}}
        for(var trigger:candidates){if(trigger.state().equals("PUSH_DISPATCHING")||!valid(trigger)){writeTrigger(change(trigger,"PUSH_INTERRUPTED","STATE_PUSH_DISPATCH_UNKNOWN_OR_STALE"));pauseExhaustedPush(trigger.subscriptionId());continue;}
            var started=change(trigger,"PUSH_DISPATCHING","");writeTrigger(started);return Optional.of(started);}
        return Optional.empty();});}
    public synchronized void preparePush(UUID id,List<dev.mineagent.runtime.api.ui.UiProtocol.Session> targets)throws Exception{
        var selected=List.copyOf(targets);if(selected.size()>32||selected.stream().map(s->s.sessionId()).distinct().count()!=selected.size())throw new IllegalArgumentException("STATE_PUSH_RECIPIENT_BUDGET");
        tx(()->{var trigger=trigger(id);if(trigger==null||!trigger.state().equals("PUSH_DISPATCHING")||!valid(trigger))throw new SecurityException("STATE_PUSH_TRIGGER_STALE");var sub=subscription(trigger.subscriptionId());var destination=Objects.requireNonNull(sub.request().push());
            for(var session:selected){var binding=session.binding();if(!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(binding)||!binding.worldId().equals(world)||!binding.ownerPackageId().equals(destination.packageId())||binding.packageRevision()!=destination.packageRevision()||!binding.entryPath().equals(destination.entryPath())||!binding.targetObjectId().equals("world:"+destination.instanceId())||session.status()!=dev.mineagent.runtime.api.ui.UiProtocol.Status.RENDERED)throw new SecurityException("STATE_PUSH_TARGET_SCOPE");}
            for(var session:selected){budget("mineagent_event_push_v1",8192);UUID delivery=UUID.nameUUIDFromBytes((world+"|state-push|"+id+"|"+session.sessionId()+"|"+session.pageGeneration()+"|"+session.controlEpoch()).getBytes(StandardCharsets.UTF_8));long at=now.getAsLong();writePush(new PushDelivery(delivery,id,sub.id(),session,"PLANNED",at,Math.min(Math.min(at+15000,session.expiresAtMillis()),sub.expiresAt()),0,0,0,""));}
            writeTrigger(change(trigger,selected.isEmpty()?"PUSH_NO_RECIPIENTS":"PUSH_WAITING",""));pauseExhaustedPush(sub.id());return null;
        });
    }
    public synchronized void failPush(UUID id,String code)throws Exception{if(!Set.of("STATE_PUSH_TARGET_UNAVAILABLE","STATE_PUSH_DISPATCH_UNCERTAIN").contains(code))throw new IllegalArgumentException("STATE_PUSH_ERROR");tx(()->{var trigger=trigger(id);if(trigger!=null&&Set.of("PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING").contains(trigger.state())){writeTrigger(change(trigger,"PUSH_INTERRUPTED",code));pauseExhaustedPush(trigger.subscriptionId());}return null;});}
    private PushDelivery push(UUID id)throws Exception{return read("mineagent_event_push_v1",id,PushDelivery.class);}
    private static boolean pushTerminal(PushDelivery row){return Set.of("READ_DELIVERED","EXPIRED","REJECTED","INTERRUPTED").contains(row.state());}
    private boolean pushCurrent(PushDelivery row)throws Exception{var trigger=trigger(row.triggerId());return !pushTerminal(row)&&now.getAsLong()<row.expiresAt()&&trigger!=null&&trigger.state().equals("PUSH_WAITING")&&valid(trigger);}
    private void writePush(PushDelivery row)throws Exception{try(var query=db.prepareStatement("INSERT INTO mineagent_event_push_v1(world,id,trigger_id,state,last_attempt,expires_at,payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,last_attempt=excluded.last_attempt,expires_at=excluded.expires_at,payload=excluded.payload")){query.setString(1,world.toString());query.setString(2,row.id().toString());query.setString(3,row.triggerId().toString());query.setString(4,row.state());query.setLong(5,row.lastAttempt());query.setLong(6,row.expiresAt());query.setString(7,json.writeValueAsString(row));query.executeUpdate();}}
    private PushDelivery pushChange(PushDelivery row,String state,int attempts,long last,long revision,String error){return new PushDelivery(row.id(),row.triggerId(),row.subscriptionId(),row.session(),state,row.createdAt(),row.expiresAt(),attempts,last,revision,error);}
    public synchronized List<PushDelivery> pushDeliveries(UUID trigger)throws Exception{var result=new ArrayList<PushDelivery>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_push_v1 WHERE world=? AND trigger_id=? ORDER BY rowid")){query.setString(1,world.toString());query.setString(2,trigger.toString());try(var rows=query.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),PushDelivery.class));}}return List.copyOf(result);}
    private void finishPushIfSettled(UUID id)throws Exception{var trigger=trigger(id);if(trigger==null||!trigger.state().equals("PUSH_WAITING"))return;var rows=pushDeliveries(id);if(rows.isEmpty()){writeTrigger(change(trigger,"PUSH_INTERRUPTED","STATE_PUSH_RECEIPTS_MISSING"));pauseExhaustedPush(trigger.subscriptionId());return;}if(rows.stream().anyMatch(row->!pushTerminal(row)))return;writeTrigger(change(trigger,rows.stream().allMatch(row->row.state().equals("READ_DELIVERED"))?"PUSH_READ_DELIVERED":"PUSH_PARTIAL_OR_FAILED",""));pauseExhaustedPush(trigger.subscriptionId());}
    public synchronized List<PushDelivery> pushCandidates(int maximum)throws Exception{
        if(maximum<1||maximum>16)throw new IllegalArgumentException("STATE_PUSH_PUMP_BUDGET");return tx(()->{var result=new ArrayList<PushDelivery>();
            try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_push_v1 WHERE world=? AND state IN ('PLANNED','SENT','READ_PRODUCED') ORDER BY last_attempt,rowid LIMIT ?")){query.setString(1,world.toString());query.setInt(2,maximum);try(var rows=query.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),PushDelivery.class));}}
            var due=new ArrayList<PushDelivery>();for(var row:result){if(!pushCurrent(row)){writePush(pushChange(row,"EXPIRED",row.attempts(),row.lastAttempt(),row.readRevision(),"STATE_PUSH_STALE_OR_EXPIRED"));finishPushIfSettled(row.triggerId());}
                else if(now.getAsLong()-row.lastAttempt()>=1000){if(row.attempts()>=3){writePush(pushChange(row,"EXPIRED",row.attempts(),row.lastAttempt(),row.readRevision(),"STATE_PUSH_NO_READ_ACK"));finishPushIfSettled(row.triggerId());}else due.add(row);}}
            return List.copyOf(due);
        });
    }
    public synchronized PushDelivery markPushSent(UUID id)throws Exception{return tx(()->{var row=push(id);if(row==null||!pushCurrent(row)||row.attempts()>=3)throw new SecurityException("STATE_PUSH_SEND_STALE");var sent=pushChange(row,row.state().equals("READ_PRODUCED")?"READ_PRODUCED":"SENT",row.attempts()+1,now.getAsLong(),row.readRevision(),"");writePush(sent);return sent;});}
    public synchronized void rejectPush(UUID id)throws Exception{tx(()->{var row=push(id);if(row!=null&&!pushTerminal(row)){writePush(pushChange(row,"REJECTED",row.attempts(),row.lastAttempt(),row.readRevision(),"STATE_PUSH_VIEW_UNAVAILABLE"));finishPushIfSettled(row.triggerId());}return null;});}
    public synchronized PushDelivery requirePushRead(UUID id,dev.mineagent.runtime.api.ui.UiProtocol.Session session)throws Exception{var row=push(id);if(row==null||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(row.session(),session)||!Set.of("SENT","READ_PRODUCED").contains(row.state())||!pushCurrent(row))throw new SecurityException("STATE_PUSH_READ_SCOPE");return row;}
    public synchronized void pushReadProduced(List<UUID> ids,dev.mineagent.runtime.api.ui.UiProtocol.Session session,long revision)throws Exception{
        if(revision<1||ids.isEmpty()||ids.size()>16||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("STATE_PUSH_READ_BATCH");tx(()->{for(var id:ids){var row=requirePushRead(id,session);writePush(pushChange(row,"READ_PRODUCED",row.attempts(),row.lastAttempt(),revision,""));}return null;});
    }
    public synchronized void acknowledgePushRead(UUID id,dev.mineagent.runtime.api.ui.UiProtocol.Session session)throws Exception{tx(()->{var row=push(id);if(row==null||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(row.session(),session))throw new SecurityException("STATE_PUSH_ACK_SCOPE");if(row.state().equals("READ_DELIVERED"))return null;if(!row.state().equals("READ_PRODUCED")||!pushCurrent(row))throw new SecurityException("STATE_PUSH_ACK_STALE");writePush(pushChange(row,"READ_DELIVERED",row.attempts(),row.lastAttempt(),row.readRevision(),""));finishPushIfSettled(row.triggerId());return null;});}
    private long pendingCount(UUID subscription)throws Exception{try(var q=db.prepareStatement("SELECT COUNT(*) FROM mineagent_event_triggers_v1 WHERE world=? AND subscription=? AND state IN ('QUEUED','CLAIMED','DISPATCHED')")){q.setString(1,world.toString());q.setString(2,subscription.toString());try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private void pauseExhaustedWake(UUID id)throws Exception{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.request().mode().equals("AGENT_WAKE")&&sub.wakesReserved()>=sub.request().maxWakes()&&pendingCount(id)==0)pause(sub,"EVENT_WAKE_BUDGET",head());}
    private List<Trigger> queued()throws Exception{var values=new ArrayList<Trigger>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND state='QUEUED' ORDER BY rowid LIMIT 8")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())values.add(json.readValue(r.getString(1),Trigger.class));}}return values;}
    private UUID taskId(UUID owner,UUID operation){return UUID.nameUUIDFromBytes(("task-start|"+world+"|"+owner+"|"+operation).getBytes(StandardCharsets.UTF_8));}
    public synchronized Optional<Trigger> claim()throws Exception{if(queued().isEmpty())return Optional.empty();return tx(()->{for(var trigger:queued()){if(!valid(trigger)){writeTrigger(change(trigger,"CANCELLED","AUTHORITY_OR_SUBSCRIPTION_CHANGED"));pauseExhaustedWake(trigger.subscriptionId());continue;}var claimed=change(trigger,"CLAIMED","");writeTrigger(claimed);return Optional.of(claimed);}return Optional.empty();});}
    public synchronized void dispatched(UUID id)throws Exception{tx(()->{var t=trigger(id);if(t==null||!t.state().equals("CLAIMED")||!valid(t))throw new IllegalStateException("EVENT_DISPATCH_STALE");writeTrigger(change(t,"DISPATCHED",""));return null;});}
    public synchronized void failed(UUID id,String code)throws Exception{tx(()->{var t=trigger(id);if(t!=null&&Set.of("QUEUED","CLAIMED","DISPATCHED").contains(t.state())){writeTrigger(change(t,"INTERRUPTED",code));pauseExhaustedWake(t.subscriptionId());}return null;});}
    private boolean valid(Trigger t)throws Exception{var sub=subscription(t.subscriptionId());if(sub==null||!sub.state().equals("ACTIVE")||sub.revision()!=t.subscriptionRevision()||now.getAsLong()>=sub.expiresAt())return false;String authority=port.authority(sub);return authority!=null&&authority.equals(t.authority());}
    public synchronized boolean permitTask(UUID task)throws Exception{var t=forTask(task).orElse(null);return t==null||t.state().equals("DISPATCHED")&&valid(t);}
    public synchronized boolean reserveModel(UUID task)throws Exception{return tx(()->{var t=forTask(task).orElse(null);if(t==null)return true;if(!t.state().equals("DISPATCHED")||!valid(t))return false;if(t.modelAttempts()>=t.maxModelCalls()){writeTrigger(change(t,"MODEL_BUDGET_EXHAUSTED","EVENT_MODEL_ATTEMPT_BUDGET"));pauseExhaustedWake(t.subscriptionId());return false;}
        writeTrigger(new Trigger(t.id(),t.subscriptionId(),t.event(),t.subscriptionRevision(),t.state(),t.authority(),t.taskId(),t.modelAttempts()+1,t.maxModelCalls(),t.createdAt(),t.error(),t.matchObservation()));return true;});}
    public synchronized void taskFinished(UUID task,String state)throws Exception{tx(()->{var t=forTask(task).orElse(null);if(t!=null&&t.state().equals("DISPATCHED")&&Set.of("COMPLETED","CANCELLED","FAILED").contains(state)){writeTrigger(change(t,state,state.equals("COMPLETED")?"":"TASK_STOPPED"));pauseExhaustedWake(t.subscriptionId());}return null;});}
    public synchronized Optional<Trigger> forTask(UUID task)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND task_id=?")){q.setString(1,world.toString());q.setString(2,task.toString());try(var r=q.executeQuery()){return r.next()?Optional.of(json.readValue(r.getString(1),Trigger.class)):Optional.empty();}}}
    public synchronized Subscription inspect(Context c,UUID id)throws Exception{authorize(c);var s=owned(c,id);port.authorizeRequest(c,s.request());return s;}
    public synchronized List<Trigger> triggers(Context c,UUID id)throws Exception{return history(c,id,0,(int)RetainedRows.MAX_RETAINED_ROWS);}
    public synchronized List<Trigger> history(Context c,UUID id,int offset,int limit)throws Exception{authorize(c);port.authorizeRequest(c,owned(c,id).request());var result=historyRows(id,offset,limit,false);authorize(c);return result;}
    public synchronized long historyCount(Context c,UUID id)throws Exception{authorize(c);port.authorizeRequest(c,owned(c,id).request());return scriptCount(id,"1=1");}
    private List<Trigger> historyRows(UUID id,int offset,int limit,boolean newest)throws Exception{if(offset<0||offset>RetainedRows.MAX_RETAINED_ROWS||limit<1||limit>RetainedRows.MAX_RETAINED_ROWS)throw new IllegalArgumentException("EVENT_HISTORY_PAGE");var result=new ArrayList<Trigger>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND subscription=? ORDER BY rowid "+(newest?"DESC":"ASC")+" LIMIT ? OFFSET ?")){query.setString(1,world.toString());query.setString(2,id.toString());query.setInt(3,limit);query.setInt(4,offset);try(var rows=query.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),Trigger.class));}}return List.copyOf(result);}
    public synchronized Subscription subscriptionForRuntime(UUID id)throws Exception{return subscription(id);}
    /** Internal producer failure, not a caller-selected subscription state mutation. Preserves existing receipts. */
    public synchronized void pauseRegionSource(UUID id,long revision,String code)throws Exception{
        if(!Set.of("REGION_SOURCE_UNAVAILABLE","REGION_PLAYER_BUDGET","REGION_AUTHORITY_CHANGED","REGION_EVENT_COMMIT_FAILED","REGION_DIMENSION_UNAVAILABLE","REGION_SUBSCRIPTION_EXPIRED").contains(code))throw new IllegalArgumentException("REGION_DIAGNOSTIC");
        tx(()->{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.revision()==revision&&PlayerRegionEvents.requested(sub.request()))pause(sub,code,head());return null;});
    }
    public synchronized void pauseObjectSource(UUID id,long revision,String code)throws Exception{
        if(!Set.of("OBJECT_EVENT_SOURCE_UNAVAILABLE","OBJECT_EVENT_CAPTURE_BACKPRESSURE","OBJECT_EVENT_COMMIT_FAILED","OBJECT_EVENT_RESOURCE_CHANGED").contains(code))throw new IllegalArgumentException("OBJECT_EVENT_DIAGNOSTIC");
        tx(()->{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.revision()==revision&&sub.request().object()!=null)pause(sub,code,head());return null;});
    }
    public synchronized void pauseScoreSource(UUID id,long revision,String code)throws Exception{
        if(!Set.of("SCORE_EVENT_AUTHORITY_CHANGED","SCORE_OBJECTIVE_MISSING","SCORE_OBJECTIVE_REPLACED","SCORE_CRITERIA_CHANGED","SCORE_HOLDER_SCAN_BUDGET","SCORE_ENTRY_BUDGET","SCORE_EVENT_COMMIT_FAILED","SCORE_EVENT_SOURCE_UNAVAILABLE","SCORE_EVENT_EXPIRED").contains(code))throw new IllegalArgumentException("SCORE_EVENT_DIAGNOSTIC");
        tx(()->{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.revision()==revision&&sub.request().score()!=null)pause(sub,code,head());return null;});
    }
    public synchronized void pauseScriptConsumer(UUID id,long revision)throws Exception{tx(()->{var sub=subscription(id);if(sub!=null&&sub.state().equals("ACTIVE")&&sub.revision()==revision&&sub.request().script()!=null)pause(sub,"SCRIPT_TARGET_OR_PERMISSION_UNAVAILABLE",head());return null;});}
    private void management(ManagementContext context,Subscription subscription,boolean activate){if(!world.equals(context.world())||subscription!=null&&!subscription.creator().scope().ownerId().equals(context.owner()))throw new SecurityException("EVENT_MANAGEMENT_OWNER");port.authorizeManagement(context,subscription,activate);}
    public synchronized ManagementPage managementList(ManagementContext context,String mode,String state,int offset,int limit)throws Exception{
        management(context,null,false);if(!Set.of("ALL","RECORD_ONLY","AGENT_WAKE","SCRIPT","STATE_PUSH").contains(mode)||!Set.of("ALL","ACTIVE","PAUSED","CANCELLED").contains(state)||offset<0||offset>RetainedRows.MAX_RETAINED_ROWS||limit<1||limit>16)throw new IllegalArgumentException("EVENT_MANAGEMENT_PAGE");
        String where=" WHERE world=? AND owner=? AND (?='ALL' OR mode=?) AND (?='ALL' OR state=?)";var result=new ArrayList<Subscription>();int total;
        try(var query=db.prepareStatement("SELECT COUNT(*) FROM mineagent_subscriptions_v1"+where)){managementFilter(query,context,mode,state);try(var rows=query.executeQuery()){total=rows.next()?rows.getInt(1):0;}}
        try(var query=db.prepareStatement("SELECT payload FROM mineagent_subscriptions_v1"+where+" ORDER BY rowid DESC LIMIT ? OFFSET ?")){managementFilter(query,context,mode,state);query.setInt(7,limit);query.setInt(8,offset);try(var rows=query.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),Subscription.class));}}
        management(context,null,false);return new ManagementPage(total,offset,offset+result.size(),total>offset+result.size(),result);
    }
    private void managementFilter(PreparedStatement query,ManagementContext context,String mode,String state)throws Exception{query.setString(1,world.toString());query.setString(2,context.owner().toString());query.setString(3,mode);query.setString(4,mode);query.setString(5,state);query.setString(6,state);}
    public synchronized boolean archivedSubscription(UUID id)throws Exception{return RetainedRows.archived(db,world,"mineagent_subscriptions_v1",id.toString());}
    public synchronized Subscription managementInspect(ManagementContext context,UUID id)throws Exception{management(context,null,false);var sub=subscription(id);if(sub==null)throw new IllegalStateException("EVENT_SUBSCRIPTION_MISSING");management(context,sub,false);return sub;}
    public synchronized List<Trigger> managementHistory(ManagementContext context,UUID id,int offset,int limit)throws Exception{managementInspect(context,id);if(limit>8)throw new IllegalArgumentException("EVENT_HISTORY_PAGE");var rows=historyRows(id,offset,limit,true);management(context,subscription(id),false);return rows;}
    public synchronized long managementHistoryCount(ManagementContext context,UUID id)throws Exception{managementInspect(context,id);return scriptCount(id,"1=1");}
    public synchronized RetentionCount managementOwnerRetention(ManagementContext context)throws Exception{
        management(context,null,false);
        var result=retentionCount("SELECT COUNT(*),COALESCE(SUM(archived),0) FROM mineagent_subscriptions_v1 WHERE world=? AND owner=?",context.owner());
        management(context,null,false);return result;
    }
    public synchronized Map<String,Object> managementRetention(ManagementContext context,UUID id)throws Exception{
        var sub=managementInspect(context,id);
        var triggers=retentionCount("SELECT COUNT(*),COALESCE(SUM(archived),0) FROM mineagent_event_triggers_v1 WHERE world=? AND subscription=?",id);
        var pushes=retentionCount("SELECT COUNT(*),COALESCE(SUM(p.archived),0) FROM mineagent_event_push_v1 p JOIN mineagent_event_triggers_v1 t ON t.world=p.world AND t.id=p.trigger_id WHERE t.world=? AND t.subscription=?",id);
        boolean archived=archivedSubscription(id),stopped=sub.state().equals("CANCELLED")||now.getAsLong()>=sub.expiresAt();
        management(context,sub,false);return Map.of("definitionArchived",archived,"triggers",triggers,"pushDeliveries",pushes,"canArchive",stopped&&(!archived||triggers.hot()>0||pushes.hot()>0),"batchLimitPerTable",256,"maximumRetainedPerTable",RetainedRows.MAX_RETAINED_ROWS,"diskReclaimed",false);
    }
    private RetentionCount retentionCount(String sql,UUID id)throws Exception{
        try(var query=db.prepareStatement(sql)){query.setString(1,world.toString());query.setString(2,id.toString());try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("RETENTION_USAGE_MISSING");long total=rows.getLong(1),archived=rows.getLong(2);return new RetentionCount(total-archived,archived,total);}}
    }
    /** Explicit owner-only housekeeping; same operation returns original counts, not a second batch. */
    public synchronized ManagementResult managementArchive(ManagementContext context,UUID operation,UUID id,long expectedRevision,boolean confirmed)throws Exception{
        if(!confirmed||expectedRevision<1)throw new IllegalArgumentException("EVENT_MANAGEMENT_CONFIRM");Objects.requireNonNull(operation);management(context,null,false);
        return tx(()->{
            var sub=managementInspect(context,id);String fingerprint=ScoreEventSampler.hash(context.world()+"|"+context.owner()+"|"+id+"|"+expectedRevision+"|ARCHIVE|true");
            var prior=read("mineagent_event_management_v1",operation,ManagementOperation.class);
            if(prior!=null){if(!prior.owner().equals(context.owner())||!prior.fingerprint().equals(fingerprint))throw new IllegalArgumentException("EVENT_OPERATION_REUSED");return new ManagementResult(prior.receipt(),true,sub.state(),sub.revision());}
            if(read("mineagent_event_operations_v1",operation,Operation.class)!=null)throw new IllegalArgumentException("EVENT_OPERATION_REUSED");
            if(sub.revision()!=expectedRevision)throw new IllegalStateException("EVENT_SUBSCRIPTION_STALE");
            if(!sub.state().equals("CANCELLED")&&now.getAsLong()<sub.expiresAt())throw new IllegalStateException("EVENT_ARCHIVE_REQUIRES_STOPPED");
            budget("mineagent_event_management_v1",8192);
            int definitions=archiveSubscription(sub);
            var rows=new ArrayList<Long>();
            try(var query=db.prepareStatement("SELECT rowid FROM mineagent_event_triggers_v1 WHERE world=? AND subscription=? AND archived=0 AND state IN ("+TERMINAL_TRIGGERS+") ORDER BY rowid LIMIT 256")){query.setString(1,world.toString());query.setString(2,id.toString());try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}
            int triggers=RetainedRows.archive(db,world,"mineagent_event_triggers_v1",rows);rows.clear();
            try(var query=db.prepareStatement("SELECT p.rowid FROM mineagent_event_push_v1 p JOIN mineagent_event_triggers_v1 t ON t.world=p.world AND t.id=p.trigger_id WHERE p.world=? AND t.subscription=? AND p.archived=0 AND p.state IN ('READ_DELIVERED','EXPIRED','REJECTED','INTERRUPTED') ORDER BY p.rowid LIMIT 256")){query.setString(1,world.toString());query.setString(2,id.toString());try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}
            int pushes=RetainedRows.archive(db,world,"mineagent_event_push_v1",rows);
            var current=subscription(id);var receipt=new ManagementReceipt(operation,id,current.state(),current.revision(),new ArchiveCounts(definitions,triggers,pushes));
            try(var query=db.prepareStatement("INSERT INTO mineagent_event_management_v1(world,id,payload) VALUES(?,?,?)")){query.setString(1,world.toString());query.setString(2,operation.toString());query.setString(3,json.writeValueAsString(new ManagementOperation(context.owner(),fingerprint,receipt)));query.executeUpdate();}
            management(context,current,false);return new ManagementResult(receipt,false,current.state(),current.revision());
        });
    }
    public synchronized ManagementResult managementState(ManagementContext context,UUID operation,UUID id,long expectedRevision,String state,boolean confirmed)throws Exception{
        if(!Set.of("ACTIVE","PAUSED","CANCELLED").contains(state)||expectedRevision<1||state.equals("ACTIVE")&&!confirmed)throw new IllegalArgumentException("EVENT_MANAGEMENT_STATE");management(context,null,false);Objects.requireNonNull(operation);
        return tx(()->{var sub=managementInspect(context,id);String fingerprint=ScoreEventSampler.hash(context.world()+"|"+context.owner()+"|"+id+"|"+expectedRevision+"|"+state+"|"+confirmed);var prior=read("mineagent_event_management_v1",operation,ManagementOperation.class);
            if(prior!=null){if(!prior.owner().equals(context.owner())||!prior.fingerprint().equals(fingerprint))throw new IllegalArgumentException("EVENT_OPERATION_REUSED");management(context,sub,false);return new ManagementResult(prior.receipt(),true,sub.state(),sub.revision());}
            if(read("mineagent_event_operations_v1",operation,Operation.class)!=null)throw new IllegalArgumentException("EVENT_OPERATION_REUSED");
            if(sub.revision()!=expectedRevision||sub.state().equals("CANCELLED")&&!state.equals("CANCELLED"))throw new IllegalStateException("EVENT_SUBSCRIPTION_STALE");management(context,sub,state.equals("ACTIVE"));budget("mineagent_event_management_v1",8192);
            Request request=sub.request();if(state.equals("ACTIVE")){
                if(now.getAsLong()>=sub.expiresAt())throw new IllegalStateException("EVENT_SUBSCRIPTION_EXPIRED");
                if(request.mode().equals("AGENT_WAKE")&&sub.wakesReserved()>=request.maxWakes()||request.script()!=null&&scriptRuns(id)>=request.script().maxRuns()||request.push()!=null&&pushSignals(id)>=request.push().maxSignals())throw new IllegalStateException("EVENT_CONSUMER_BUDGET_EXHAUSTED");
                if(!sub.state().equals(state))request=port.bindManagementResume(context,sub);
                if(request.feedback()!=null&&request.mode().equals("AGENT_WAKE")){var candidate=request.feedback();if(allSubscriptions().stream().anyMatch(other->!other.id().equals(id)&&other.state().equals("ACTIVE")&&other.expiresAt()>now.getAsLong()&&other.request().mode().equals("AGENT_WAKE")&&other.request().feedback()!=null&&other.creator().scope().ownerId().equals(context.owner())&&other.creator().scope().agentId().equals(sub.creator().scope().agentId())&&candidate.overlaps(other.request().feedback())))throw new IllegalStateException("FEEDBACK_CONSUMER_ALREADY_BOUND");}
            }
            var next=sub;if(!sub.state().equals(state)){next=new Subscription(sub.id(),sub.creator(),request,state,sub.revision()+1,head(),sub.wakesReserved(),sub.lastWakeAt(),sub.expiresAt(),"");writeSubscription(next);cancelPending(id,"SUBSCRIPTION_CHANGED");}
            var receipt=new ManagementReceipt(operation,id,next.state(),next.revision());try(var query=db.prepareStatement("INSERT INTO mineagent_event_management_v1(world,id,payload) VALUES(?,?,?)")){query.setString(1,world.toString());query.setString(2,operation.toString());query.setString(3,json.writeValueAsString(new ManagementOperation(context.owner(),fingerprint,receipt)));query.executeUpdate();}
            management(context,next,state.equals("ACTIVE"));return new ManagementResult(receipt,false,next.state(),next.revision());
        });
    }
    public synchronized List<Subscription> subscriptionsForRuntime()throws Exception{return List.copyOf(allSubscriptions());}
    public synchronized List<Trigger> forEvent(UUID id)throws Exception{var values=new ArrayList<Trigger>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_event_triggers_v1 WHERE world=? AND event_id=? ORDER BY rowid")){query.setString(1,world.toString());query.setString(2,id.toString());try(var rows=query.executeQuery()){while(rows.next())values.add(json.readValue(rows.getString(1),Trigger.class));}}return List.copyOf(values);}
    public record RecordedSignal(long sequence,UUID eventId){}
    public synchronized RecordedSignal latestRecorded(UUID subscription)throws Exception{try(var q=db.prepareStatement("SELECT e.sequence,e.id FROM mineagent_event_triggers_v1 t JOIN mineagent_events_v1 e ON e.world=t.world AND e.id=t.event_id WHERE t.world=? AND t.subscription=? AND t.state='RECORDED' ORDER BY e.sequence DESC LIMIT 1")){q.setString(1,world.toString());q.setString(2,subscription.toString());try(var r=q.executeQuery()){return r.next()?new RecordedSignal(r.getLong(1),UUID.fromString(r.getString(2))):new RecordedSignal(0,null);}}}
    public synchronized Optional<Event> event(UUID id)throws Exception{return Optional.ofNullable(read("mineagent_events_v1",id,Event.class));}
    public synchronized List<Trigger> taskBindings()throws Exception{return allTriggers().stream().filter(t->t.taskId()!=null).toList();}
    private void pause(Subscription s,String code,long cursor)throws Exception{writeSubscription(new Subscription(s.id(),s.creator(),s.request(),"PAUSED",s.revision()+1,cursor,s.wakesReserved(),s.lastWakeAt(),s.expiresAt(),code));cancelPending(s.id(),code);}
    private void cancelPending(UUID id,String code)throws Exception{for(var t:allTriggers())if(t.subscriptionId().equals(id)&&Set.of("QUEUED","CLAIMED","DISPATCHED","SCRIPT_QUEUED","SCRIPT_DISPATCHING","PUSH_QUEUED","PUSH_DISPATCHING","PUSH_WAITING").contains(t.state()))writeTrigger(change(t,t.state().startsWith("PUSH_")?"PUSH_INTERRUPTED":t.state().equals("SCRIPT_DISPATCHING")?"SCRIPT_INTERRUPTED":t.state().equals("SCRIPT_QUEUED")?"SCRIPT_CANCELLED":"CANCELLED",code));}
    private Trigger change(Trigger t,String state,String error){return new Trigger(t.id(),t.subscriptionId(),t.event(),t.subscriptionRevision(),state,t.authority(),t.taskId(),t.modelAttempts(),t.maxModelCalls(),t.createdAt(),error,t.matchObservation(),t.scriptResult());}
    private UUID stable(String prefix,UUID op){return UUID.nameUUIDFromBytes((world+"|"+prefix+"|"+op).getBytes(StandardCharsets.UTF_8));}
    private long head()throws Exception{try(var q=db.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM mineagent_events_v1 WHERE world=?")){q.setString(1,world.toString());try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private void budget(String table,int maximum)throws Exception{
        RetainedRows.requireCapacity(db,world,table);if(RetainedRows.usage(db,world,table).hot()>=maximum)archiveCompleted(table,256);
        if(RetainedRows.usage(db,world,table).hot()>=maximum)throw new IllegalStateException("EVENT_STORAGE_BUDGET");
    }
    private int archiveCompleted(String table,int maximum)throws Exception{
        if(table.equals("mineagent_subscriptions_v1"))return archiveSubscriptions(null,null,maximum);
        String condition=table.equals("mineagent_event_triggers_v1")?" AND state IN ("+TERMINAL_TRIGGERS+")":table.equals("mineagent_event_push_v1")?" AND state IN ('READ_DELIVERED','EXPIRED','REJECTED','INTERRUPTED')":"";
        var rows=new ArrayList<Long>();try(var query=db.prepareStatement("SELECT rowid FROM "+table+" WHERE world=? AND archived=0"+condition+" ORDER BY rowid LIMIT ?")){query.setString(1,world.toString());query.setInt(2,maximum);try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}
        return RetainedRows.archive(db,world,table,rows);
    }
    private int archiveSubscriptions(UUID owner,UUID agent,int maximum)throws Exception{
        int count=0;for(var sub:allSubscriptions()){
            if(owner!=null&&!owner.equals(sub.creator().scope().ownerId())||agent!=null&&!agent.equals(sub.creator().scope().agentId()))continue;
            count+=archiveSubscription(sub);if(count>=maximum)break;
        }return count;
    }
    private int archiveSubscription(Subscription sub)throws Exception{
        if(archivedSubscription(sub.id()))return 0;
        boolean expired=now.getAsLong()>=sub.expiresAt();if(!sub.state().equals("CANCELLED")&&!expired)return 0;
        if(expired&&!sub.state().equals("CANCELLED"))pause(sub,"EXPIRED_RETAINED",head());
        if(pendingCount(sub.id())+scriptPending(sub.id())+pushPending(sub.id())!=0)return 0;
        try(var update=db.prepareStatement("UPDATE mineagent_subscriptions_v1 SET archived=1 WHERE world=? AND id=? AND archived=0")){update.setString(1,world.toString());update.setString(2,sub.id().toString());return update.executeUpdate();}
    }
    private List<Subscription> allSubscriptions()throws Exception{return list("mineagent_subscriptions_v1",Subscription.class);}
    private List<Trigger> allTriggers()throws Exception{return list("mineagent_event_triggers_v1",Trigger.class);}
    private <T>List<T> list(String table,Class<T> type)throws Exception{var values=new ArrayList<T>();try(var q=db.prepareStatement("SELECT payload FROM "+table+" WHERE world=? AND archived=0 ORDER BY rowid")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())values.add(json.readValue(r.getString(1),type));}}return values;}
    private Subscription subscription(UUID id)throws Exception{return read("mineagent_subscriptions_v1",id,Subscription.class);}
    private Trigger trigger(UUID id)throws Exception{return read("mineagent_event_triggers_v1",id,Trigger.class);}
    private Operation operation(UUID id)throws Exception{if(read("mineagent_event_management_v1",id,ManagementOperation.class)!=null)throw new IllegalArgumentException("EVENT_OPERATION_REUSED");return read("mineagent_event_operations_v1",id,Operation.class);}
    private <T>T read(String table,UUID id,Class<T> type)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM "+table+" WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){return r.next()?json.readValue(r.getString(1),type):null;}}}
    private void writeSubscription(Subscription s)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_subscriptions_v1(world,id,owner,agent,state,payload,mode) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,payload=excluded.payload,mode=excluded.mode")){q.setString(1,world.toString());q.setString(2,s.id().toString());q.setString(3,s.creator().scope().ownerId().toString());q.setString(4,s.creator().scope().agentId().toString());q.setString(5,s.state());q.setString(6,json.writeValueAsString(s));q.setString(7,s.request().mode());q.executeUpdate();}}
    private void writeTrigger(Trigger t)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_event_triggers_v1(world,id,subscription,event_id,state,task_id,payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,payload=excluded.payload")){q.setString(1,world.toString());q.setString(2,t.id().toString());q.setString(3,t.subscriptionId().toString());q.setString(4,t.event().id().toString());q.setString(5,t.state());q.setString(6,t.taskId()==null?null:t.taskId().toString());q.setString(7,json.writeValueAsString(t));q.executeUpdate();}}
    private void writeOperation(UUID id,Operation value)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_event_operations_v1(world,id,payload) VALUES(?,?,?)")){q.setString(1,world.toString());q.setString(2,id.toString());q.setString(3,json.writeValueAsString(value));q.executeUpdate();}}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T tx(Work<T> work)throws Exception{try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{T value=work.run();s.execute("COMMIT");return value;}catch(Exception|Error e){try{s.execute("ROLLBACK");}catch(Exception r){e.addSuppressed(r);}throw e;}}}
    @Override public synchronized void close()throws Exception{try{db.close();}finally{try{lock.close();}finally{channel.close();}}}
}
