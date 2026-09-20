package dev.mineagent.runtime.core.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.persistence.RetainedRows;
import dev.mineagent.runtime.api.directory.ObjectRef;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Durable selector membership and immutable per-trigger resolution. Neither a delivery nor a display grant. */
public final class AudienceStore implements AutoCloseable {
    public record Context(ObjectDirectory.Scope scope,UUID serverEpoch,UUID operationId){public Context{Objects.requireNonNull(scope);Objects.requireNonNull(serverEpoch);Objects.requireNonNull(operationId);}}
    public record Create(String mode,ObjectQuery query,int ttlSeconds){
        public Create{if(!Set.of("SNAPSHOT","LIVE_AT_TRIGGER").contains(mode)||query==null||query.kind()!=ObjectRef.Kind.PLAYER||!query.cursor().isEmpty()||ttlSeconds<1||ttlSeconds>604800)throw new IllegalArgumentException("AUDIENCE_SELECTOR_INVALID");}
    }
    public record Definition(UUID audienceId,UUID worldId,UUID ownerId,UUID agentId,UUID originTaskId,long originIntent,String mode,ObjectQuery query,List<UUID> members,String state,long revision,long createdAt,long expiresAt){public Definition{
        Objects.requireNonNull(audienceId);Objects.requireNonNull(worldId);Objects.requireNonNull(ownerId);Objects.requireNonNull(agentId);Objects.requireNonNull(originTaskId);members=List.copyOf(members);
        if(originIntent<1||!Set.of("SNAPSHOT","LIVE_AT_TRIGGER").contains(mode)||query==null||query.kind()!=ObjectRef.Kind.PLAYER||!query.cursor().isEmpty()||members.size()>64||new HashSet<>(members).size()!=members.size()||mode.equals("LIVE_AT_TRIGGER")&&!members.isEmpty()||!Set.of("ACTIVE","REVOKED").contains(state)||revision<1||revision>9_007_199_254_740_991L||expiresAt<=createdAt||expiresAt-createdAt>604800000L)throw new IllegalArgumentException("AUDIENCE_DEFINITION_INVALID");
    }}
    public record Recipient(UUID playerId,String status,ObjectRef ref){
        public Recipient{Objects.requireNonNull(playerId);if(status==null||!Set.of("ELIGIBLE","UNAVAILABLE","FILTER_CHANGED","GENERATION_CHANGED").contains(status)||(status.equals("ELIGIBLE")?(ref==null||ref.kind()!=ObjectRef.Kind.PLAYER||!ref.id().equals(playerId.toString())):ref!=null))throw new IllegalArgumentException("AUDIENCE_RECIPIENT_INVALID");}
    }
    public record Snapshot(UUID snapshotId,UUID audienceId,UUID triggerId,long definitionRevision,Context context,String state,List<Recipient> recipients,long resolvedAt,long expiresAt,String error){public Snapshot{
        Objects.requireNonNull(snapshotId);Objects.requireNonNull(audienceId);Objects.requireNonNull(triggerId);Objects.requireNonNull(context);recipients=List.copyOf(recipients);
        if(definitionRevision<1||!Set.of("RESOLVING","RESOLVED","FAILED","INTERRUPTED").contains(state)||recipients.size()>64||recipients.stream().map(Recipient::playerId).distinct().count()!=recipients.size()||recipients.stream().anyMatch(r->r.ref()!=null&&!r.ref().worldId().equals(context.scope().worldId()))||!state.equals("RESOLVED")&&!recipients.isEmpty()||resolvedAt<0||error==null||!error.matches("[A-Z0-9_]{0,80}"))throw new IllegalArgumentException("AUDIENCE_SNAPSHOT_INVALID");
    }}
    public record Operation(UUID operationId,String kind,Context context,String fingerprint,String state,UUID subjectId,String result,String error,long createdAt){}
    public record PreparedCreate(Operation operation,Create request,boolean dispatch){}
    private record PreparedResolve(Operation operation,Definition definition,Snapshot snapshot,boolean dispatch){}
    public interface Port {
        void authorize(Context context);
        List<ObjectRef> select(Context context,ObjectQuery query);
        Recipient assess(Context context,ObjectQuery query,UUID playerId);
    }
    private final Connection db;private final UUID world;private final Clock clock;private final Port port;private final FileChannel channel;private final FileLock lock;
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private AudienceStore(Connection db,UUID world,Clock clock,Port port,FileChannel channel,FileLock lock)throws Exception{
        this.db=db;this.world=world;this.clock=clock;this.port=port;this.channel=channel;this.lock=lock;
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_audience_definitions_v1(world TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,agent TEXT NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_audience_operations_v1(world TEXT NOT NULL,id TEXT NOT NULL,subject TEXT NOT NULL,kind TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_audience_snapshots_v1(world TEXT NOT NULL,id TEXT NOT NULL,audience TEXT NOT NULL,trigger_id TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id),UNIQUE(world,audience,trigger_id))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_audience_operation_subject_v1 ON mineagent_audience_operations_v1(world,subject,state)");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_audience_definition_owner_v1 ON mineagent_audience_definitions_v1(world,owner,agent)");
        }
        tx(()->{RetainedRows.initialize(db,world,List.of("mineagent_audience_definitions_v1","mineagent_audience_operations_v1","mineagent_audience_snapshots_v1"));boolean expiry=false;
            try(var query=db.createStatement();var rows=query.executeQuery("PRAGMA table_info(mineagent_audience_definitions_v1)")){while(rows.next())if(rows.getString("name").equals("expires_at"))expiry=true;}
            if(!expiry)try(var statement=db.createStatement()){statement.execute("ALTER TABLE mineagent_audience_definitions_v1 ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0");}
            var old=new ArrayList<Definition>();try(var query=db.prepareStatement("SELECT payload FROM mineagent_audience_definitions_v1 WHERE world=? AND expires_at=0")){query.setString(1,world.toString());try(var rows=query.executeQuery()){while(rows.next())old.add(json.readValue(rows.getString(1),Definition.class));}}
            try(var update=db.prepareStatement("UPDATE mineagent_audience_definitions_v1 SET expires_at=? WHERE world=? AND id=?")){for(var d:old){update.setLong(1,d.expiresAt());update.setString(2,world.toString());update.setString(3,d.audienceId().toString());update.executeUpdate();}}
            try(var statement=db.createStatement()){statement.execute("CREATE INDEX IF NOT EXISTS mineagent_audience_retention_v1 ON mineagent_audience_definitions_v1(world,archived,owner,agent,expires_at)");}return null;
        });
        tx(()->{for(var op:operationsIn("PENDING","LINKED"))writeOperation(new Operation(op.operationId(),op.kind(),op.context(),op.fingerprint(),"INTERRUPTED",op.subjectId(),op.result(),"RESTART_NO_REPLAY",op.createdAt()));
            try(var q=db.prepareStatement("SELECT payload FROM mineagent_audience_snapshots_v1 WHERE world=? AND state='RESOLVING'")){q.setString(1,world.toString());try(var rows=q.executeQuery()){var pending=new ArrayList<Snapshot>();while(rows.next())pending.add(json.readValue(rows.getString(1),Snapshot.class));for(var old:pending)writeSnapshot(new Snapshot(old.snapshotId(),old.audienceId(),old.triggerId(),old.definitionRevision(),old.context(),"INTERRUPTED",List.of(),0,old.expiresAt(),"RESTART_NO_REPLAY"));}}return null;});
    }
    public static AudienceStore open(Path path,UUID world,Clock clock,Port port)throws Exception{
        Objects.requireNonNull(world);Objects.requireNonNull(clock);Objects.requireNonNull(port);Path p=path.toAbsolutePath().normalize();Files.createDirectories(p.getParent());
        var channel=FileChannel.open(p.resolveSibling(p.getFileName()+".audience-"+world+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=null;Connection db=null;
        try{try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IllegalStateException("AUDIENCE_STORE_ALREADY_OPEN",busy);}if(lock==null)throw new IllegalStateException("AUDIENCE_STORE_ALREADY_OPEN");db=DriverManager.getConnection("jdbc:sqlite:"+p);return new AudienceStore(db,world,clock,port,channel,lock);}
        catch(Exception e){if(db!=null)try{db.close();}catch(Exception close){e.addSuppressed(close);}if(lock!=null)try{lock.close();}catch(Exception close){e.addSuppressed(close);}try{channel.close();}catch(Exception close){e.addSuppressed(close);}throw e;}
    }
    private void authorize(Context c){if(!world.equals(c.scope().worldId()))throw new SecurityException("AUDIENCE_WORLD_MISMATCH");port.authorize(c);}
    private boolean samePrincipal(Context a,Context b){return a.scope().worldId().equals(b.scope().worldId())&&a.scope().ownerId().equals(b.scope().ownerId())&&a.scope().agentId().equals(b.scope().agentId());}
    private String base(Context c){var s=c.scope();return s.worldId()+"|"+s.ownerId()+"|"+s.agentId()+"|"+s.taskId()+"|"+s.intentRevision();}
    private String fingerprint(Context c,String kind,Object request)throws Exception{return hash(base(c)+"|"+kind+"|"+json.writeValueAsString(request));}
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static UUID id(String prefix,UUID world,UUID operation){return UUID.nameUUIDFromBytes((prefix+"|"+world+"|"+operation).getBytes(StandardCharsets.UTF_8));}
    private void active(Definition d,long expected){if(d.revision()!=expected)throw new IllegalStateException("AUDIENCE_REVISION_CHANGED");if(!d.state().equals("ACTIVE"))throw new IllegalStateException("AUDIENCE_REVOKED");if(clock.millis()>=d.expiresAt())throw new IllegalStateException("AUDIENCE_EXPIRED");}
    private Definition owned(Context c,UUID audience)throws Exception{var d=definition(audience);if(d==null||!d.worldId().equals(world)||!d.ownerId().equals(c.scope().ownerId())||!d.agentId().equals(c.scope().agentId()))throw new SecurityException("AUDIENCE_NOT_OWNED");return d;}
    private Operation prior(Context c,String kind,Object request)throws Exception{var old=op(c.operationId());if(old!=null&&!old.fingerprint().equals(fingerprint(c,kind,request)))throw new IllegalArgumentException("AUDIENCE_OPERATION_REUSED");return old;}
    private void budget(String table,int maximum)throws Exception{RetainedRows.requireCapacity(db,world,table);if(RetainedRows.usage(db,world,table).hot()>=maximum)archive(table,null,null);if(RetainedRows.usage(db,world,table).hot()>=maximum)throw new IllegalStateException("AUDIENCE_STORAGE_BUDGET");}
    private int archive(String table,UUID owner,UUID agent)throws Exception{
        String condition=table.equals("mineagent_audience_definitions_v1")?" AND (state='REVOKED' OR expires_at<=?)":table.equals("mineagent_audience_snapshots_v1")?" AND state IN ('RESOLVED','FAILED','INTERRUPTED')":" AND state IN ('APPLIED','FAILED','INTERRUPTED')";
        if(owner!=null)condition+=" AND owner=? AND agent=?";var ids=new ArrayList<Long>();try(var query=db.prepareStatement("SELECT rowid FROM "+table+" WHERE world=? AND archived=0"+condition+" ORDER BY rowid LIMIT 256")){int i=1;query.setString(i++,world.toString());if(table.equals("mineagent_audience_definitions_v1"))query.setLong(i++,clock.millis());if(owner!=null){query.setString(i++,owner.toString());query.setString(i,agent.toString());}try(var rows=query.executeQuery()){while(rows.next())ids.add(rows.getLong(1));}}return RetainedRows.archive(db,world,table,ids);
    }
    private long ownerCount(Context c)throws Exception{try(var query=db.prepareStatement("SELECT COUNT(*) FROM mineagent_audience_definitions_v1 WHERE world=? AND owner=? AND agent=? AND archived=0")){query.setString(1,world.toString());query.setString(2,c.scope().ownerId().toString());query.setString(3,c.scope().agentId().toString());try(var rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}}}
    private void ownerBudget(Context c)throws Exception{if(ownerCount(c)>=64){archive("mineagent_audience_definitions_v1",c.scope().ownerId(),c.scope().agentId());if(ownerCount(c)>=64)throw new IllegalStateException("AUDIENCE_OWNER_BUDGET");}}
    private void applied(Operation op){if(!op.state().equals("APPLIED"))throw new IllegalStateException("AUDIENCE_OPERATION_"+op.state());}
    public synchronized PreparedCreate beginCreate(Context c,Create request)throws Exception{
        authorize(c);return tx(()->{authorize(c);var old=prior(c,"CREATE",request);if(old!=null)return new PreparedCreate(old,request,false);
            budget("mineagent_audience_operations_v1",8192);budget("mineagent_audience_definitions_v1",4096);
            ownerBudget(c);
            var operation=new Operation(c.operationId(),"CREATE",c,fingerprint(c,"CREATE",request),"PENDING",id("audience",world,c.operationId()),"","",clock.millis());writeOperation(operation);return new PreparedCreate(operation,request,true);});
    }
    public synchronized Definition create(Context c,Create request)throws Exception{
        var prepared=beginCreate(c,request);if(!prepared.dispatch()){applied(prepared.operation());return owned(c,prepared.operation().subjectId());}
        try{List<ObjectRef> refs=request.mode().equals("SNAPSHOT")?checked(port.select(c,request.query())):List.of();
            for(var ref:refs){var now=port.assess(c,request.query(),UUID.fromString(ref.id()));if(!now.status().equals("ELIGIBLE")||!ref.equals(now.ref()))throw new IllegalStateException("AUDIENCE_SELECTION_CHANGED");}
            authorize(c);return tx(()->{var op=op(c.operationId());pendingAttempt(c,op);budget("mineagent_audience_definitions_v1",4096);ownerBudget(c);var now=clock.millis();var d=new Definition(op.subjectId(),world,c.scope().ownerId(),c.scope().agentId(),c.scope().taskId(),c.scope().intentRevision(),request.mode(),request.query(),refs.stream().map(r->UUID.fromString(r.id())).sorted().toList(),"ACTIVE",1,now,Math.addExact(now,request.ttlSeconds()*1000L));writeDefinition(d);finishOperation(op,"APPLIED",json.writeValueAsString(d),"");return d;});
        }catch(Exception failure){fail(c.operationId(),safe(failure));throw failure;}
    }
    private List<ObjectRef> checked(List<ObjectRef> refs){
        if(refs==null||refs.size()>64||refs.stream().anyMatch(r->r==null||!r.worldId().equals(world)||r.kind()!=ObjectRef.Kind.PLAYER)||refs.stream().map(ObjectRef::id).distinct().count()!=refs.size())throw new IllegalArgumentException("AUDIENCE_RECIPIENT_BUDGET_OR_SCOPE");
        return refs.stream().sorted(Comparator.comparing(ObjectRef::id)).toList();
    }
    private void pendingAttempt(Context c,Operation op){authorize(c);if(op==null||!op.state().equals("PENDING")||!op.context().equals(c))throw new IllegalStateException("AUDIENCE_ATTEMPT_STALE");}
    private PreparedResolve beginResolve(Context c,UUID audience,long expected,UUID trigger)throws Exception{
        authorize(c);var request=List.of(audience,expected,trigger);return tx(()->{
            authorize(c);var d=owned(c,audience);active(d,expected);var old=prior(c,"RESOLVE",request);if(old!=null){var snapshot=snapshot(old.subjectId());if(old.state().equals("APPLIED"))return new PreparedResolve(old,d,snapshot,false);throw new IllegalStateException("AUDIENCE_OPERATION_"+old.state());}
            budget("mineagent_audience_operations_v1",8192);var existing=trigger(audience,trigger);
            if(existing!=null){if(existing.definitionRevision()!=expected||!base(existing.context()).equals(base(c)))throw new IllegalArgumentException("AUDIENCE_TRIGGER_CONTEXT_REUSED");
                String state=existing.state().equals("RESOLVED")?"APPLIED":existing.state().equals("RESOLVING")?"LINKED":existing.state();var operation=new Operation(c.operationId(),"RESOLVE",c,fingerprint(c,"RESOLVE",request),state,existing.snapshotId(),json.writeValueAsString(existing),existing.error(),clock.millis());writeOperation(operation);return new PreparedResolve(operation,d,existing,false);}
            budget("mineagent_audience_snapshots_v1",4096);UUID snapshotId=id("audience-snapshot",world,c.operationId());
            var snapshot=new Snapshot(snapshotId,audience,trigger,expected,c,"RESOLVING",List.of(),0,d.expiresAt(),"");var operation=new Operation(c.operationId(),"RESOLVE",c,fingerprint(c,"RESOLVE",request),"PENDING",snapshotId,"","",clock.millis());writeSnapshot(snapshot);writeOperation(operation);return new PreparedResolve(operation,d,snapshot,true);
        });
    }
    public synchronized Snapshot resolve(Context c,UUID audience,long expected,UUID trigger)throws Exception{
        Objects.requireNonNull(trigger);var p=beginResolve(c,audience,expected,trigger);if(!p.dispatch()){applied(p.operation());return p.snapshot();}
        try{
            List<ObjectRef> selected=p.definition().mode().equals("LIVE_AT_TRIGGER")?checked(port.select(c,p.definition().query())):List.of();
            var members=p.definition().mode().equals("SNAPSHOT")?p.definition().members():selected.stream().map(r->UUID.fromString(r.id())).toList();
            var recipients=new ArrayList<Recipient>();for(var member:members){var r=port.assess(c,p.definition().query(),member);if(!member.equals(r.playerId())||r.ref()!=null&&!r.ref().worldId().equals(world))throw new IllegalArgumentException("AUDIENCE_RECIPIENT_SCOPE");
                var original=selected.stream().filter(ref->ref.id().equals(member.toString())).findFirst().orElse(null);if(original!=null&&r.ref()!=null&&!original.equals(r.ref()))r=new Recipient(member,"GENERATION_CHANGED",null);recipients.add(r);}
            authorize(c);return tx(()->{var operation=op(c.operationId());pendingAttempt(c,operation);active(owned(c,audience),expected);var result=new Snapshot(p.snapshot().snapshotId(),audience,trigger,expected,c,"RESOLVED",recipients,clock.millis(),p.definition().expiresAt(),"");writeSnapshot(result);finishLinked(result,"APPLIED",json.writeValueAsString(result),"");return result;});
        }catch(Exception failure){fail(c.operationId(),safe(failure));throw failure;}
    }
    public synchronized Definition revoke(Context c,UUID audience,long expected)throws Exception{
        authorize(c);var request=List.of(audience,expected);return tx(()->{authorize(c);var d=owned(c,audience);var old=prior(c,"REVOKE",request);if(old!=null){applied(old);return d;}
            if(d.revision()!=expected)throw new IllegalStateException("AUDIENCE_REVISION_CHANGED");budget("mineagent_audience_operations_v1",8192);
            var next=new Definition(d.audienceId(),d.worldId(),d.ownerId(),d.agentId(),d.originTaskId(),d.originIntent(),d.mode(),d.query(),d.members(),"REVOKED",d.revision()+1,d.createdAt(),d.expiresAt());writeDefinition(next);
            writeOperation(new Operation(c.operationId(),"REVOKE",c,fingerprint(c,"REVOKE",request),"APPLIED",audience,json.writeValueAsString(next),"",clock.millis()));return next;});
    }
    /** Native standing-definition lookup; callers must enforce current owner/Agent/grants. */
    public synchronized Definition definitionForRuntime(UUID id)throws Exception{return definition(id);}
    public synchronized Definition inspect(Context c,UUID audience)throws Exception{authorize(c);return owned(c,audience);}
    public synchronized Operation operation(Context c,UUID operation)throws Exception{authorize(c);var value=op(operation);if(value==null||!samePrincipal(c,value.context()))throw new SecurityException("AUDIENCE_OPERATION_NOT_OWNED");return value;}
    public synchronized Snapshot inspectSnapshot(Context c,UUID id)throws Exception{authorize(c);var value=snapshot(id);if(value==null||!samePrincipal(c,value.context()))throw new SecurityException("AUDIENCE_SNAPSHOT_NOT_OWNED");owned(c,value.audienceId());return value;}
    private void fail(UUID id,String code)throws Exception{tx(()->{var operation=op(id);if(operation==null||!Set.of("PENDING","LINKED").contains(operation.state()))return null;
        if(operation.kind().equals("RESOLVE")){var s=snapshot(operation.subjectId());if(s!=null&&s.state().equals("RESOLVING")){var failed=new Snapshot(s.snapshotId(),s.audienceId(),s.triggerId(),s.definitionRevision(),s.context(),"FAILED",List.of(),0,s.expiresAt(),code);writeSnapshot(failed);finishLinked(failed,"FAILED",json.writeValueAsString(failed),code);}}
        else finishOperation(operation,"FAILED","",code);return null;});}
    private String safe(Exception failure){String message=failure.getMessage();return message!=null&&message.matches("[A-Z0-9_]{1,80}")?message:"AUDIENCE_RESOLUTION_FAILED";}
    private void finishLinked(Snapshot snapshot,String state,String result,String error)throws Exception{
        var values=new ArrayList<Operation>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_audience_operations_v1 WHERE world=? AND subject=? AND kind='RESOLVE' AND state IN ('PENDING','LINKED')")){q.setString(1,world.toString());q.setString(2,snapshot.snapshotId().toString());try(var rows=q.executeQuery()){while(rows.next())values.add(json.readValue(rows.getString(1),Operation.class));}}
        for(var op:values)finishOperation(op,state,result,error);
    }
    private void finishOperation(Operation op,String state,String result,String error)throws Exception{writeOperation(new Operation(op.operationId(),op.kind(),op.context(),op.fingerprint(),state,op.subjectId(),result,error,op.createdAt()));}
    private List<Operation> operationsIn(String first,String second)throws Exception{var values=new ArrayList<Operation>();try(var q=db.prepareStatement("SELECT payload FROM mineagent_audience_operations_v1 WHERE world=? AND state IN (?,?)")){q.setString(1,world.toString());q.setString(2,first);q.setString(3,second);try(var r=q.executeQuery()){while(r.next())values.add(json.readValue(r.getString(1),Operation.class));}}return values;}
    private Definition definition(UUID id)throws Exception{return read("mineagent_audience_definitions_v1",id,Definition.class);}
    private Operation op(UUID id)throws Exception{return read("mineagent_audience_operations_v1",id,Operation.class);}
    private Snapshot snapshot(UUID id)throws Exception{return read("mineagent_audience_snapshots_v1",id,Snapshot.class);}
    private <T>T read(String table,UUID id,Class<T> type)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM "+table+" WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){return r.next()?json.readValue(r.getString(1),type):null;}}}
    private Snapshot trigger(UUID audience,UUID trigger)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM mineagent_audience_snapshots_v1 WHERE world=? AND audience=? AND trigger_id=?")){q.setString(1,world.toString());q.setString(2,audience.toString());q.setString(3,trigger.toString());try(var r=q.executeQuery()){return r.next()?json.readValue(r.getString(1),Snapshot.class):null;}}}
    private void writeDefinition(Definition d)throws Exception{String payload=json.writeValueAsString(d);RetainedRows.requirePayloadCapacity(db,world,"mineagent_audience_definitions_v1",d.audienceId(),payload,256L*1024*1024,d.state().equals("REVOKED"));try(var q=db.prepareStatement("INSERT INTO mineagent_audience_definitions_v1(world,id,owner,agent,revision,state,payload,expires_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET revision=excluded.revision,state=excluded.state,payload=excluded.payload,expires_at=excluded.expires_at")){q.setString(1,world.toString());q.setString(2,d.audienceId().toString());q.setString(3,d.ownerId().toString());q.setString(4,d.agentId().toString());q.setLong(5,d.revision());q.setString(6,d.state());q.setString(7,payload);q.setLong(8,d.expiresAt());q.executeUpdate();}}
    private void writeOperation(Operation o)throws Exception{String payload=json.writeValueAsString(o);RetainedRows.requirePayloadCapacity(db,world,"mineagent_audience_operations_v1",o.operationId(),payload,256L*1024*1024,Set.of("APPLIED","FAILED","INTERRUPTED").contains(o.state()));try(var q=db.prepareStatement("INSERT INTO mineagent_audience_operations_v1(world,id,subject,kind,state,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,payload=excluded.payload")){q.setString(1,world.toString());q.setString(2,o.operationId().toString());q.setString(3,o.subjectId().toString());q.setString(4,o.kind());q.setString(5,o.state());q.setString(6,payload);q.executeUpdate();}}
    private void writeSnapshot(Snapshot s)throws Exception{String payload=json.writeValueAsString(s);RetainedRows.requirePayloadCapacity(db,world,"mineagent_audience_snapshots_v1",s.snapshotId(),payload,256L*1024*1024,!s.state().equals("RESOLVING"));try(var q=db.prepareStatement("INSERT INTO mineagent_audience_snapshots_v1(world,id,audience,trigger_id,state,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET state=excluded.state,payload=excluded.payload")){q.setString(1,world.toString());q.setString(2,s.snapshotId().toString());q.setString(3,s.audienceId().toString());q.setString(4,s.triggerId().toString());q.setString(5,s.state());q.setString(6,payload);q.executeUpdate();}}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T tx(Work<T> work)throws Exception{try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{T result=work.run();s.execute("COMMIT");return result;}catch(Exception|Error failure){try{s.execute("ROLLBACK");}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}}}
    @Override public synchronized void close()throws Exception{try{db.close();}finally{try{lock.close();}finally{channel.close();}}}
}
