package dev.mineagent.runtime.core.feedback;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.core.persistence.RetainedRows;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** Trusted semantic feedback acceptance and an atomic outbox, not a second task executor. */
public final class UiFeedbackStore implements AutoCloseable {
    public record ConsumerBinding(UUID subscriptionId,long revision,String authority){public ConsumerBinding{Objects.requireNonNull(subscriptionId);if(revision<1||authority==null||authority.isBlank()||authority.length()>4096)throw new IllegalArgumentException("FEEDBACK_CONSUMER_BINDING");}}
    public record Scope(UUID worldId,UUID deliveryId,UUID authorId,UUID viewerId,UUID actorId,String actorKind,UUID ownerId,UUID agentId,UUID originTaskId,long originIntentRevision,UUID conversationId,
                        UUID packageId,long packageRevision,String canonicalSha256,String entry,String policySha256,String eventName,
                        UiFeedbackPolicy.Event policy,String authority,UUID causation,List<UUID> causalChain,ConsumerBinding consumer,FeedbackDataBinding dataBinding) {
        public Scope(UUID worldId,UUID deliveryId,UUID authorId,UUID viewerId,UUID actorId,String actorKind,UUID ownerId,UUID agentId,UUID originTaskId,long originIntentRevision,UUID conversationId,UUID packageId,long packageRevision,String canonicalSha256,String entry,String policySha256,String eventName,UiFeedbackPolicy.Event policy,String authority,UUID causation,List<UUID> causalChain,ConsumerBinding consumer){this(worldId,deliveryId,authorId,viewerId,actorId,actorKind,ownerId,agentId,originTaskId,originIntentRevision,conversationId,packageId,packageRevision,canonicalSha256,entry,policySha256,eventName,policy,authority,causation,causalChain,consumer,null);}
        public Scope(UUID worldId,UUID deliveryId,UUID authorId,UUID viewerId,UUID actorId,String actorKind,UUID ownerId,UUID agentId,UUID originTaskId,long originIntentRevision,UUID conversationId,UUID packageId,long packageRevision,String canonicalSha256,String entry,String policySha256,String eventName,UiFeedbackPolicy.Event policy,String authority,UUID causation,List<UUID> causalChain){this(worldId,deliveryId,authorId,viewerId,actorId,actorKind,ownerId,agentId,originTaskId,originIntentRevision,conversationId,packageId,packageRevision,canonicalSha256,entry,policySha256,eventName,policy,authority,causation,causalChain,null);}
        public Scope withConsumer(ConsumerBinding binding){return new Scope(worldId,deliveryId,authorId,viewerId,actorId,actorKind,ownerId,agentId,originTaskId,originIntentRevision,conversationId,packageId,packageRevision,canonicalSha256,entry,policySha256,eventName,policy,authority,causation,causalChain,binding,dataBinding);}
        public Scope withDataBinding(FeedbackDataBinding binding){return new Scope(worldId,deliveryId,authorId,viewerId,actorId,actorKind,ownerId,agentId,originTaskId,originIntentRevision,conversationId,packageId,packageRevision,canonicalSha256,entry,policySha256,eventName,policy,authority,causation,causalChain,consumer,binding);}
        public Scope {
            for(Object required:List.of(worldId,deliveryId,authorId,viewerId,actorId,ownerId,agentId,originTaskId,conversationId,packageId,policy))Objects.requireNonNull(required);
            if(!authorId.equals(viewerId)||!authorId.equals(actorId)||!"PLAYER".equals(actorKind)||originIntentRevision<1||originIntentRevision>9007199254740991L||packageRevision<1||packageRevision>9007199254740991L||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||policySha256==null||!policySha256.matches("[a-f0-9]{64}")
                    ||entry==null||!entry.matches("ui/[A-Za-z0-9_./-]+\\.html")||entry.contains("..")||eventName==null||!eventName.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                    ||Set.of("prototype","constructor").contains(eventName)||authority==null||authority.isBlank()||authority.length()>2048)throw new IllegalArgumentException("FEEDBACK_SCOPE");
            causalChain=List.copyOf(causalChain);if(causalChain.size()>8||new HashSet<>(causalChain).size()!=causalChain.size())throw new IllegalArgumentException("FEEDBACK_CAUSAL_CHAIN");
        }
    }
    public record Document(UUID leaseId,UUID sessionId,String viewId,String documentId,long pageGeneration,long controlEpoch) {
        public Document {Objects.requireNonNull(leaseId);Objects.requireNonNull(sessionId);if(!("delivery-"+leaseId).equals(viewId)||documentId==null||documentId.isBlank()||documentId.length()>128||pageGeneration<1||controlEpoch<1)throw new IllegalArgumentException("FEEDBACK_DOCUMENT");}
    }
    public record Completion(UUID operationId,String code,String result) {
        public Completion {Objects.requireNonNull(operationId);if(code==null||!code.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("FEEDBACK_COMPLETION_CODE");result=dev.mineagent.runtime.core.ui.WorldUiData.normalize(result);if(result.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("FEEDBACK_COMPLETION_BUDGET");}
    }
    public record ReplyIntent(UUID operationId,UUID consumerId,String text,String sha256){public ReplyIntent{Objects.requireNonNull(operationId);Objects.requireNonNull(consumerId);if(text==null||text.isBlank()||text.getBytes(StandardCharsets.UTF_8).length>8192||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("FEEDBACK_REPLY");}}
    public record Item(UUID id,UUID operationId,Scope scope,Document document,String payload,long createdAt,String state,long revision,
                       boolean exported,UUID consumerOperationId,Completion completion,String error,long sequence,ReplyIntent reply,FeedbackTransactionPlan dataPlan) {}
    public interface Port {
        /** Current Native identity, visible/painted document, signed schema and explicit feedback grant, also checked at commit. */
        void authorizeSubmission(Scope scope,Document document);
        /** Independent task/resource authority. Closing a page alone need not revoke it. */
        boolean standing(Scope scope);
        boolean documentCurrent(Scope scope,Document document);
        /** Must verify an actual task/native transaction receipt; never just model text or dispatch success. */
        boolean verifyCompletion(Item item,Completion proof);
        default void changed(UUID id){}
    }
    private static final Set<String> TERMINAL=Set.of("RECORDED","COMPLETED","REJECTED","CANCELLED","FAILED","INTERRUPTED");
    private static final String TERMINAL_SQL="'RECORDED','COMPLETED','REJECTED','CANCELLED','FAILED','INTERRUPTED'";
    private static final long MAX_BYTES=512L*1024*1024,RESULT_RESERVE=512L*1024;
    private final Connection db;private final UUID world;private final LongSupplier now;private final Port port;private final FileChannel channel;private final FileLock lock;
    private final ObjectMapper json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);private boolean closed;
    private UiFeedbackStore(Connection db,UUID world,LongSupplier now,Port port,FileChannel channel,FileLock lock)throws Exception {
        this.db=db;this.world=world;this.now=now;this.port=port;this.channel=channel;this.lock=lock;
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_ui_feedback_v1(world TEXT NOT NULL,id TEXT NOT NULL,operation TEXT NOT NULL,author TEXT NOT NULL,delivery TEXT NOT NULL,fingerprint TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id),UNIQUE(world,operation))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_ui_feedback_author_v1 ON mineagent_ui_feedback_v1(world,author)");
        }
        tx(()->{RetainedRows.initialize(db,world,List.of("mineagent_ui_feedback_v1"));var columns=new HashSet<String>();try(var query=db.createStatement();var rows=query.executeQuery("PRAGMA table_info(mineagent_ui_feedback_v1)")){while(rows.next())columns.add(rows.getString("name"));}
            try(var statement=db.createStatement()){for(var column:List.of("state","mode","event_name"))if(!columns.contains(column))statement.execute("ALTER TABLE mineagent_ui_feedback_v1 ADD COLUMN "+column+" TEXT NOT NULL DEFAULT ''");for(var column:List.of("exported","sequence","created_at","reserved_bytes"))if(!columns.contains(column))statement.execute("ALTER TABLE mineagent_ui_feedback_v1 ADD COLUMN "+column+" INTEGER NOT NULL DEFAULT 0");}
            var old=new ArrayList<Item>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_ui_feedback_v1 WHERE world=? AND state=''")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())old.add(json.readValue(rows.getString(1),Item.class));}}
            try(var update=db.prepareStatement("UPDATE mineagent_ui_feedback_v1 SET state=?,mode=?,event_name=?,exported=?,sequence=?,created_at=? WHERE world=? AND id=?")){for(var item:old){update.setString(1,item.state());update.setString(2,item.scope().policy().mode());update.setString(3,item.scope().eventName());update.setBoolean(4,item.exported());update.setLong(5,item.sequence());update.setLong(6,item.createdAt());update.setString(7,world.toString());update.setString(8,item.id().toString());update.executeUpdate();}}
            try(var statement=db.createStatement()){statement.execute("CREATE INDEX IF NOT EXISTS mineagent_feedback_work_v1 ON mineagent_ui_feedback_v1(world,state,exported,mode,sequence)");statement.execute("CREATE INDEX IF NOT EXISTS mineagent_feedback_lifetime_v1 ON mineagent_ui_feedback_v1(world,author,delivery,event_name,created_at)");statement.execute("CREATE INDEX IF NOT EXISTS mineagent_feedback_rate_v1 ON mineagent_ui_feedback_v1(world,author,created_at)");statement.execute("CREATE INDEX IF NOT EXISTS mineagent_feedback_sequence_v1 ON mineagent_ui_feedback_v1(world,sequence)");}
            return null;
        });
        tx(()->{for(var item:queryRows(" AND state='PROCESSING'",List.of(),0,(int)RetainedRows.MAX_RETAINED_ROWS,false))save(change(item,n->{n.put("state","INTERRUPTED");n.put("error","SERVER_RESTART_NO_REPLAY");}));return null;});
    }
    public static UiFeedbackStore open(Path path,UUID world,LongSupplier now,Port port)throws Exception {
        Objects.requireNonNull(world);Objects.requireNonNull(now);Objects.requireNonNull(port);Path p=path.toAbsolutePath().normalize();Files.createDirectories(p.getParent());
        var channel=FileChannel.open(p.resolveSibling(p.getFileName()+".feedback-"+world+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;Connection db=null;
        try {
            try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IllegalStateException("FEEDBACK_STORE_ALREADY_OPEN",busy);}if(lock==null)throw new IllegalStateException("FEEDBACK_STORE_ALREADY_OPEN");
            db=DriverManager.getConnection("jdbc:sqlite:"+p);return new UiFeedbackStore(db,world,now,port,channel,lock);
        }catch(Exception e){if(db!=null)try{db.close();}catch(Exception x){e.addSuppressed(x);}if(lock!=null)try{lock.close();}catch(Exception x){e.addSuppressed(x);}try{channel.close();}catch(Exception x){e.addSuppressed(x);}throw e;}
    }
    private void authorize(Scope scope,Document document){if(closed||!world.equals(scope.worldId()))throw new SecurityException("FEEDBACK_WORLD");port.authorizeSubmission(scope,document);}
    public synchronized Item submit(Scope scope,Document document,UUID operation,String payload)throws Exception {
        Objects.requireNonNull(operation);Objects.requireNonNull(document);authorize(scope,document);String normalized=UiFeedbackPolicy.validate(scope.policy(),payload);
        return tx(()->{
            // Document intentionally stays out of the logical operation fingerprint:
            // a newly admitted document may explicitly inspect/retry the same author
            // operation, but every retry first passes current document authorization.
            String fp=hash(List.of(scope,normalized));Item previous=findOperation(operation,fp);if(previous!=null){authorize(scope,document);return previous;}
            long timestamp=now.getAsLong();if(timestamp<0)throw new IllegalStateException("FEEDBACK_CLOCK");budgetNew();long count,latest;
            try(var query=db.prepareStatement("SELECT COUNT(*),MAX(created_at) FROM mineagent_ui_feedback_v1 WHERE world=? AND author=? AND delivery=? AND event_name=?")){query.setString(1,world.toString());query.setString(2,scope.authorId().toString());query.setString(3,scope.deliveryId().toString());query.setString(4,scope.eventName());try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("FEEDBACK_COUNTS_UNAVAILABLE");count=rows.getLong(1);latest=rows.getObject(2)==null?-1:rows.getLong(2);}}
            if(count>=scope.policy().maxEvents())throw new IllegalStateException("FEEDBACK_LIFETIME_BUDGET");
            if(latest>=0&&(timestamp<latest||timestamp-latest<scope.policy().cooldownMillis()))throw new IllegalStateException("FEEDBACK_COOLDOWN");
            if(countRows(" AND author=? AND created_at>?",List.of(scope.authorId().toString(),Long.toString(timestamp-60000)))>=32)throw new IllegalStateException("FEEDBACK_AUTHOR_RATE");
            UUID id=UUID.nameUUIDFromBytes((world+"|ui-feedback|"+operation).getBytes(StandardCharsets.UTF_8));
            var item=new Item(id,operation,scope,document,normalized,timestamp,"ACCEPTED",1,false,null,null,"",Math.addExact(head(),1),null,null);insert(item,fp);authorize(scope,document);return item;
        });
    }
    public synchronized Item inspect(UUID author,UUID id)throws Exception{var item=load(id);if(closed||item==null||!item.scope().authorId().equals(author))throw new SecurityException("FEEDBACK_NOT_OWNED");return item;}
    public synchronized Item forTarget(UUID owner,UUID agent,UUID id)throws Exception{var item=load(id);if(closed||item==null||!item.scope().ownerId().equals(owner)||!item.scope().agentId().equals(agent)||!port.standing(item.scope()))throw new SecurityException("FEEDBACK_TARGET_DENIED");return item;}
    private boolean permitted(Item item){return !closed&&port.standing(item.scope())&&(!item.scope().policy().lifecycle().equals("PAGE_BOUND")||port.documentCurrent(item.scope(),item.document()));}
    private Item current(UUID id)throws Exception{var item=load(id);if(item==null||!permitted(item))throw new SecurityException("FEEDBACK_AUTHORITY_REVOKED");return item;}
    public synchronized List<Item> pendingExports(int limit)throws Exception{if(limit<1||limit>16)throw new IllegalArgumentException("FEEDBACK_EXPORT_LIMIT");reconcile();return queryRows(" AND state='ACCEPTED' AND exported=0",List.of(),0,limit,false);}
    public synchronized Item exportable(UUID id)throws Exception{var item=current(id);if(!item.state().equals("ACCEPTED")||item.exported())throw new IllegalStateException("FEEDBACK_EXPORT_STATE");return item;}
    public synchronized void exported(UUID id)throws Exception{tx(()->{var item=current(id);if(item.exported())return null;if(!item.state().equals("ACCEPTED"))throw new IllegalStateException("FEEDBACK_EXPORT_STATE");save(change(item,n->{n.put("exported",true);if(item.scope().policy().mode().equals("RECORD_ONLY"))n.put("state","RECORDED");}));current(id);return null;});}
    public synchronized Item processing(UUID id,UUID consumer)throws Exception{Objects.requireNonNull(consumer);return tx(()->{var item=current(id);if(item.state().equals("PROCESSING")&&consumer.equals(item.consumerOperationId()))return item;if(!item.state().equals("ACCEPTED")||!item.exported()||item.scope().policy().mode().equals("RECORD_ONLY"))throw new IllegalStateException("FEEDBACK_PROCESSING_STATE");var next=change(item,n->{n.put("state","PROCESSING");n.put("consumerOperationId",consumer.toString());});save(next);current(id);return next;});}
    public synchronized ReplyIntent reserveReply(UUID id,UUID consumer,UUID operation,String text)throws Exception{
        Objects.requireNonNull(consumer);Objects.requireNonNull(operation);String digest=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(text.getBytes(StandardCharsets.UTF_8));var intent=new ReplyIntent(operation,consumer,text,digest);
        return tx(()->{var item=current(id);if(!consumer.equals(item.consumerOperationId()))throw new SecurityException("FEEDBACK_CONSUMER_MISMATCH");if(!item.state().equals("PROCESSING")||!item.scope().policy().mode().equals("AGENT_WAKE"))throw new IllegalStateException("FEEDBACK_REPLY_STATE");if(item.reply()!=null){if(!item.reply().equals(intent))throw new IllegalStateException("FEEDBACK_REPLY_ALREADY_RESERVED");return item.reply();}save(change(item,n->n.set("reply",json.valueToTree(intent))));current(id);return intent;});
    }
    public synchronized FeedbackTransactionPlan reserveDataPlan(UUID id,UUID consumer,FeedbackTransactionPlan plan)throws Exception{
        Objects.requireNonNull(plan);return tx(()->{var item=current(id);if(!Objects.equals(item.consumerOperationId(),consumer))throw new SecurityException("FEEDBACK_CONSUMER_MISMATCH");if(!item.state().equals("PROCESSING")||!item.scope().policy().mode().equals("DETERMINISTIC")||item.scope().dataBinding()==null)throw new IllegalStateException("FEEDBACK_DATA_PLAN_STATE");item.scope().dataBinding().validate(plan.transaction());if(item.dataPlan()!=null){if(!item.dataPlan().equals(plan))throw new IllegalStateException("FEEDBACK_DATA_PLAN_ALREADY_RESERVED");return item.dataPlan();}save(change(item,n->n.set("dataPlan",json.valueToTree(plan))));current(id);return plan;});
    }
    public synchronized Item dataResult(UUID id,Completion proof)throws Exception{
        Objects.requireNonNull(proof);return tx(()->{
            var item=current(id);
            if(!item.scope().policy().mode().equals("DETERMINISTIC")||item.dataPlan()==null||!Set.of("SHARED_TRANSACTION_APPLIED","SHARED_TRANSACTION_CONFLICT").contains(proof.code()))throw new IllegalStateException("FEEDBACK_DATA_RESULT_STATE");
            boolean replay=Set.of("COMPLETED","REJECTED").contains(item.state());
            if(replay&&!Objects.equals(item.completion(),proof))throw new IllegalStateException("FEEDBACK_DATA_RESULT_CHANGED");
            if(!replay&&!item.state().equals("PROCESSING"))throw new IllegalStateException("FEEDBACK_DATA_RESULT_STATE");
            if(!port.verifyCompletion(item,proof))throw new SecurityException("FEEDBACK_DATA_RESULT_UNVERIFIED");
            var next=replay?item:change(item,n->{n.put("state",proof.code().equals("SHARED_TRANSACTION_APPLIED")?"COMPLETED":"REJECTED");n.set("completion",json.valueToTree(proof));if(proof.code().equals("SHARED_TRANSACTION_CONFLICT"))n.put("error","SHARED_TRANSACTION_CONFLICT");});
            if(!replay)save(next);current(id);if(!port.verifyCompletion(next,proof))throw new SecurityException("FEEDBACK_DATA_RESULT_AUTHORITY_CHANGED");return next;
        });
    }
    public synchronized void interruptConsumer(UUID id,String reason)throws Exception{tx(()->{var item=load(id);if(item!=null&&!TERMINAL.contains(item.state()))save(change(item,n->{n.put("state","INTERRUPTED");n.put("error",reason!=null&&reason.matches("[A-Z0-9_]{1,80}")?reason:"FEEDBACK_CONSUMER_INTERRUPTED");}));return null;});}
    public synchronized void rejectDispatch(UUID id,String reason)throws Exception{tx(()->{var item=load(id);if(item!=null&&item.state().equals("ACCEPTED"))save(change(item,n->{n.put("state","REJECTED");n.put("error",reason!=null&&reason.matches("[A-Z0-9_]{1,80}")?reason:"FEEDBACK_DISPATCH_REJECTED");}));return null;});}
    public synchronized long head()throws Exception{if(closed)throw new IllegalStateException("FEEDBACK_STORE_CLOSED");try(var query=db.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM mineagent_ui_feedback_v1 WHERE world=?")){query.setString(1,world.toString());try(var rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}}}
    public synchronized Item complete(UUID id,Completion proof)throws Exception{Objects.requireNonNull(proof);return tx(()->{var item=current(id);if(item.scope().policy().mode().equals("DETERMINISTIC"))throw new IllegalStateException("FEEDBACK_DATA_RESULT_REQUIRED");if(item.state().equals("COMPLETED")&&proof.equals(item.completion()))return item;if(!item.state().equals("PROCESSING"))throw new IllegalStateException("FEEDBACK_COMPLETION_STATE");if(!port.verifyCompletion(item,proof))throw new SecurityException("FEEDBACK_COMPLETION_NOT_VERIFIED");var next=change(item,n->{n.put("state","COMPLETED");n.set("completion",json.valueToTree(proof));});save(next);current(id);if(!port.verifyCompletion(next,proof))throw new SecurityException("FEEDBACK_COMPLETION_AUTHORITY_CHANGED");return next;});}
    public synchronized void reconcile()throws Exception{if(closed)throw new IllegalStateException("FEEDBACK_STORE_CLOSED");tx(()->{for(var item:liveRows())if(!permitted(item))save(change(item,n->{n.put("state","CANCELLED");n.put("error","FEEDBACK_AUTHORITY_REVOKED");}));return null;});}
    public synchronized List<Item> rows()throws Exception{if(closed)throw new IllegalStateException("FEEDBACK_STORE_CLOSED");return List.copyOf(all());}
    public synchronized Optional<Item> runtimeItem(UUID id)throws Exception{if(closed)throw new IllegalStateException("FEEDBACK_STORE_CLOSED");return Optional.ofNullable(load(id));}
    public synchronized List<Item> liveRows()throws Exception{return queryRows(" AND state NOT IN ("+TERMINAL_SQL+")",List.of(),0,(int)RetainedRows.MAX_RETAINED_ROWS,false);}
    public synchronized List<Item> readyConsumers(String mode,int limit)throws Exception{if(!Set.of("DETERMINISTIC","AGENT_WAKE").contains(mode)||limit<1||limit>4096)throw new IllegalArgumentException("FEEDBACK_CONSUMER_QUERY");return queryRows(" AND state='ACCEPTED' AND exported=1 AND mode=?",List.of(mode),0,limit,false);}
    public synchronized List<Item> authorHistory(UUID author,UUID delivery,String state,int offset,int limit)throws Exception{
        if(author==null||!Set.of("ALL","ACCEPTED","PROCESSING","RECORDED","COMPLETED","REJECTED","CANCELLED","FAILED","INTERRUPTED").contains(state)||limit>16)throw new IllegalArgumentException("FEEDBACK_HISTORY_FILTER");var args=new ArrayList<String>();args.add(author.toString());String filter=" AND author=?";if(delivery!=null){filter+=" AND delivery=?";args.add(delivery.toString());}if(!state.equals("ALL")){filter+=" AND state=?";args.add(state);}return queryRows(filter,args,offset,limit,true);
    }
    public synchronized long authorCount(UUID author,UUID delivery,String state)throws Exception{if(author==null||!Set.of("ALL","ACCEPTED","PROCESSING","RECORDED","COMPLETED","REJECTED","CANCELLED","FAILED","INTERRUPTED").contains(state))throw new IllegalArgumentException("FEEDBACK_HISTORY_FILTER");var args=new ArrayList<String>();args.add(author.toString());String filter=" AND author=?";if(delivery!=null){filter+=" AND delivery=?";args.add(delivery.toString());}if(!state.equals("ALL")){filter+=" AND state=?";args.add(state);}return countRows(filter,args);}
    public synchronized boolean archived(UUID id)throws Exception{return RetainedRows.archived(db,world,"mineagent_ui_feedback_v1",id.toString());}
    public synchronized Map<String,Long> authorUsage(UUID author)throws Exception{try(var query=db.prepareStatement("SELECT COUNT(*),COALESCE(SUM(archived),0) FROM mineagent_ui_feedback_v1 WHERE world=? AND author=?")){query.setString(1,world.toString());query.setString(2,author.toString());try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("FEEDBACK_USAGE_UNAVAILABLE");long total=rows.getLong(1),archived=rows.getLong(2);return Map.of("hot",total-archived,"archived",archived,"total",total,"maximumHot",4096L,"maximumRetained",RetainedRows.MAX_RETAINED_ROWS,"maximumBytes",MAX_BYTES);}}}
    private List<Item> queryRows(String filter,List<String> values,int offset,int limit,boolean newest)throws Exception{
        if(closed||offset<0||offset>RetainedRows.MAX_RETAINED_ROWS||limit<1||limit>RetainedRows.MAX_RETAINED_ROWS)throw new IllegalArgumentException("FEEDBACK_PAGE");var result=new ArrayList<Item>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_ui_feedback_v1 WHERE world=?"+filter+" ORDER BY sequence "+(newest?"DESC":"ASC")+",rowid "+(newest?"DESC":"ASC")+" LIMIT ? OFFSET ?")){int i=1;query.setString(i++,world.toString());for(var value:values)query.setString(i++,value);query.setInt(i++,limit);query.setInt(i,offset);try(var rows=query.executeQuery()){while(rows.next())result.add(json.readValue(rows.getString(1),Item.class));}}return List.copyOf(result);
    }
    private long countRows(String filter,List<String> values)throws Exception{try(var query=db.prepareStatement("SELECT COUNT(*) FROM mineagent_ui_feedback_v1 WHERE world=?"+filter)){int i=1;query.setString(i++,world.toString());for(var value:values)query.setString(i++,value);try(var rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}}}
    private void budgetNew()throws Exception{
        RetainedRows.requireCapacity(db,world,"mineagent_ui_feedback_v1");if(RetainedRows.usage(db,world,"mineagent_ui_feedback_v1").hot()>=4096){var ids=new ArrayList<Long>();try(var query=db.prepareStatement("SELECT rowid FROM mineagent_ui_feedback_v1 WHERE world=? AND archived=0 AND state IN ("+TERMINAL_SQL+") ORDER BY rowid LIMIT 256")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())ids.add(rows.getLong(1));}}RetainedRows.archive(db,world,"mineagent_ui_feedback_v1",ids);}
        if(RetainedRows.usage(db,world,"mineagent_ui_feedback_v1").hot()>=4096)throw new IllegalStateException("FEEDBACK_WORLD_BUDGET");
    }
    private long reserveFor(Item item,String payload)throws Exception{
        long oldBytes=0,oldReserve=0;String state="";boolean exists=false;try(var query=db.prepareStatement("SELECT state,length(CAST(payload AS BLOB)),reserved_bytes FROM mineagent_ui_feedback_v1 WHERE world=? AND id=?")){query.setString(1,world.toString());query.setString(2,item.id().toString());try(var rows=query.executeQuery()){if(rows.next()){exists=true;state=rows.getString(1);oldBytes=rows.getLong(2);oldReserve=rows.getLong(3);}}}
        long bytes=payload.getBytes(StandardCharsets.UTF_8).length;long reserved=item.state().equals("PROCESSING")?(state.equals("PROCESSING")?Math.max(0,oldReserve-Math.max(0,bytes-oldBytes)):RESULT_RESERVE):0;long totalReserve;
        try(var query=db.prepareStatement("SELECT COALESCE(SUM(reserved_bytes),0) FROM mineagent_ui_feedback_v1 WHERE world=? AND state='PROCESSING'")){query.setString(1,world.toString());try(var rows=query.executeQuery()){totalReserve=rows.next()?rows.getLong(1):0;}}
        if(oldReserve<0||totalReserve<0)throw new IllegalStateException("FEEDBACK_RESERVE_INVALID");long growth=bytes+reserved-oldBytes-oldReserve;boolean settling=exists&&TERMINAL.contains(item.state())&&bytes-oldBytes<=4096;
        if(growth>0&&RetainedRows.usage(db,world,"mineagent_ui_feedback_v1").payloadBytes()+totalReserve+growth>MAX_BYTES&&!settling)throw new IllegalStateException("FEEDBACK_RESULT_STORAGE_BUDGET");return reserved;
    }
    private String hash(Object value)throws Exception{return dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(json.writeValueAsBytes(value));}
    private Item change(Item item,Consumer<ObjectNode> edit)throws Exception{var n=(ObjectNode)json.valueToTree(item);edit.accept(n);n.put("revision",item.revision()+1);return json.treeToValue(n,Item.class);}
    private Item findOperation(UUID operation,String fingerprint)throws Exception{try(var q=db.prepareStatement("SELECT fingerprint,payload FROM mineagent_ui_feedback_v1 WHERE world=? AND operation=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(!r.next())return null;if(!r.getString(1).equals(fingerprint))throw new IllegalArgumentException("FEEDBACK_OPERATION_REUSED");return json.readValue(r.getString(2),Item.class);}}}
    private Item load(UUID id)throws Exception{Objects.requireNonNull(id);try(var q=db.prepareStatement("SELECT payload FROM mineagent_ui_feedback_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){return r.next()?json.readValue(r.getString(1),Item.class):null;}}}
    private List<Item> all()throws Exception{var rows=new ArrayList<Item>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_ui_feedback_v1 WHERE world=? ORDER BY rowid")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())rows.add(json.readValue(r.getString(1),Item.class));}}return rows;}
    private void insert(Item item,String fp)throws Exception{String payload=json.writeValueAsString(item);long reserved=reserveFor(item,payload);try(var q=db.prepareStatement("INSERT INTO mineagent_ui_feedback_v1(world,id,operation,author,delivery,fingerprint,payload,state,mode,event_name,exported,sequence,created_at,reserved_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){q.setString(1,world.toString());q.setString(2,item.id().toString());q.setString(3,item.operationId().toString());q.setString(4,item.scope().authorId().toString());q.setString(5,item.scope().deliveryId().toString());q.setString(6,fp);q.setString(7,payload);q.setString(8,item.state());q.setString(9,item.scope().policy().mode());q.setString(10,item.scope().eventName());q.setBoolean(11,item.exported());q.setLong(12,item.sequence());q.setLong(13,item.createdAt());q.setLong(14,reserved);q.executeUpdate();}port.changed(item.id());}
    private void save(Item item)throws Exception{String payload=json.writeValueAsString(item);long reserved=reserveFor(item,payload);try(var q=db.prepareStatement("UPDATE mineagent_ui_feedback_v1 SET payload=?,state=?,exported=?,reserved_bytes=? WHERE world=? AND id=?")){q.setString(1,payload);q.setString(2,item.state());q.setBoolean(3,item.exported());q.setLong(4,reserved);q.setString(5,world.toString());q.setString(6,item.id().toString());if(q.executeUpdate()!=1)throw new IllegalStateException("FEEDBACK_ROW_MISSING");}port.changed(item.id());}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T tx(Work<T> work)throws Exception{try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{T value=work.run();s.execute("COMMIT");return value;}catch(Exception|Error e){try{s.execute("ROLLBACK");}catch(Exception failure){e.addSuppressed(failure);}throw e;}}}
    @Override public synchronized void close()throws Exception{if(closed)return;closed=true;try{db.close();}finally{try{lock.close();}finally{channel.close();}}}
}
