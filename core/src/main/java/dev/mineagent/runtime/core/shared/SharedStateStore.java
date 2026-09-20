package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.persistence.RetainedRows;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** A namespace transaction, its idempotent receipt, and its refetch notification share one SQLite commit. */
public final class SharedStateStore implements AutoCloseable {
    public record Scope(UUID world,UUID pack,UUID instance,String namespace){public Scope{Objects.requireNonNull(world);Objects.requireNonNull(pack);Objects.requireNonNull(instance);SharedJson.name(namespace);}}
    /** All identity fields are supplied by a trusted Native binding, never by page JSON. */
    public record Context(Scope scope,UUID owner,UUID actor,long packageRevision,String canonical,String actorKind){
        public Context(Scope scope,UUID owner,UUID actor,long packageRevision,String canonical){this(scope,owner,actor,packageRevision,canonical,"PLAYER");}
        public Context{Objects.requireNonNull(scope);Objects.requireNonNull(owner);Objects.requireNonNull(actor);if(packageRevision<1||canonical==null||!canonical.matches("[0-9a-f]{64}")||!Set.of("PACKAGE","PLAYER","AGENT","FEEDBACK").contains(actorKind))throw new IllegalArgumentException("SHARED_CONTEXT");}
        boolean ownerContext(){return actorKind.equals("PACKAGE")||!actorKind.equals("FEEDBACK")&&actor.equals(owner);}
    }
    @FunctionalInterface public interface Authority{boolean current(Context context,boolean write,boolean admin);}
    public record Receipt(String status,long revision,long schemaVersion,UUID eventId,String error){}
    public record Snapshot(long revision,long schemaVersion,Map<String,JsonNode> values,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) Map<String,Long> expiresAt){
        public Snapshot(long revision,long schemaVersion,Map<String,JsonNode> values){this(revision,schemaVersion,values,Map.of());}
        public Snapshot{values=Collections.unmodifiableMap(new TreeMap<>(values));expiresAt=expiresAt==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(expiresAt));}
    }
    public record Notice(UUID eventId,long revision,Set<String> keys,boolean snapshotRequired){public Notice{keys=Set.copyOf(keys);}}
    public record Feed(long revision,long cursor,boolean more,List<Notice> events){public Feed{events=List.copyOf(events);}}
    private record Entry(String key,String subject,JsonNode value,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) long expiresAt){
        Entry(String key,String subject,JsonNode value){this(key,subject,value,0);}
        Entry{Objects.requireNonNull(key);Objects.requireNonNull(subject);Objects.requireNonNull(value);if(expiresAt<0||expiresAt>=SharedJson.SAFE_INTEGER)throw new IllegalArgumentException("SHARED_STORED_EXPIRY_INVALID");}
    }
    private record Namespace(UUID owner,long revision,SharedStateSchema schema,Map<String,Entry> records){}
    private record Operation(Context context,String fingerprint,Receipt receipt){}
    public record Change(String key,String subject){}
    public record Provenance(UUID taskId,long intentRevision,List<UUID> subscriptionChain,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID consumerTriggerId,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID consumerSubscriptionId,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID scheduleOccurrenceId,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID scheduleDefinitionId){
        public Provenance(UUID taskId,long intentRevision,List<UUID> subscriptionChain,UUID consumerTriggerId,UUID consumerSubscriptionId){this(taskId,intentRevision,subscriptionChain,consumerTriggerId,consumerSubscriptionId,null,null);}
        public Provenance(UUID taskId,long intentRevision,List<UUID> subscriptionChain){this(taskId,intentRevision,subscriptionChain,null,null);}
        public static final Provenance NONE=new Provenance(null,0,List.of());
        public Provenance{subscriptionChain=List.copyOf(subscriptionChain);
            if((scheduleOccurrenceId==null)!=(scheduleDefinitionId==null)||scheduleOccurrenceId!=null&&(taskId!=null||intentRevision!=0||consumerTriggerId!=null||consumerSubscriptionId!=null))throw new IllegalArgumentException("SHARED_SCHEDULE_PROVENANCE");
            if((consumerTriggerId==null)!=(consumerSubscriptionId==null)||consumerTriggerId!=null&&(taskId!=null||intentRevision!=0||!subscriptionChain.contains(consumerSubscriptionId))||consumerTriggerId==null&&scheduleOccurrenceId==null&&(taskId==null?(intentRevision!=0||!subscriptionChain.isEmpty()):intentRevision<1))throw new IllegalArgumentException("SHARED_PROVENANCE");
            if(subscriptionChain.size()>8||new HashSet<>(subscriptionChain).size()!=subscriptionChain.size())throw new IllegalArgumentException("SHARED_CAUSAL_CHAIN");}
        public static Provenance script(UUID trigger,UUID subscription,List<UUID> inherited){var chain=new ArrayList<>(inherited);if(!chain.contains(subscription))chain.add(subscription);return new Provenance(null,0,chain,Objects.requireNonNull(trigger),Objects.requireNonNull(subscription));}
        public static Provenance schedule(UUID occurrence,UUID schedule,List<UUID> inherited){return new Provenance(null,0,inherited,null,null,Objects.requireNonNull(occurrence),Objects.requireNonNull(schedule));}
    }
    public record Description(long revision,long schemaVersion,Map<String,SharedStateSchema.Field> fields){public Description{fields=Collections.unmodifiableMap(new TreeMap<>(fields));}}
    public record MigrationPlan(String status,long revision,long schemaVersion,long targetSchemaVersion,int beforeRecords,int afterRecords,int renamedRecords,int droppedRecords,int addedRecords,String error,boolean targetSchemaMatches,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) int resetTtlRecords){
        public MigrationPlan(String status,long revision,long schemaVersion,long targetSchemaVersion,int beforeRecords,int afterRecords,int renamedRecords,int droppedRecords,int addedRecords,String error,boolean targetSchemaMatches){this(status,revision,schemaVersion,targetSchemaVersion,beforeRecords,afterRecords,renamedRecords,droppedRecords,addedRecords,error,targetSchemaMatches,0);}
    }
    private record Migrated(Namespace namespace,int renamed,int dropped,int added,int resetTtl){}
    public record RuntimeSchema(UUID owner,long schemaVersion,Map<String,SharedStateSchema.Field> fields){}
    public record Export(Scope scope,String payloadHash,Event event){}
    public record Event(UUID id,long revision,boolean schema,UUID author,String actorKind,long packageRevision,String canonical,List<Change> changes,UUID operation,long occurredAt,Provenance origin){
        public Event{changes=List.copyOf(changes);origin=origin==null?Provenance.NONE:origin;}
    }
    public record ExpirySweep(int namespaces,int records,boolean more){}
    public record ExpiryBinding(UUID owner,long packageRevision,String canonical){public ExpiryBinding{Objects.requireNonNull(owner);if(packageRevision<1||canonical==null||!canonical.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SHARED_EXPIRY_BINDING");}}
    @FunctionalInterface public interface ExpiryResolver{ExpiryBinding current(Scope scope)throws Exception;}
    private final Connection db;private final UUID world;private final Authority authority;private boolean closed;private long clockBase,clockNano;
    private SharedStateStore(Connection db,UUID world,Authority authority)throws Exception{
        this.db=db;this.world=world;this.authority=authority;
        try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");}
        transaction(()->{try(var s=db.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_shared_namespaces_v1(world TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_shared_operations_v1(world TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_shared_event_exports_v1(world TEXT NOT NULL,event TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(world,event))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_shared_outbox_v1(world TEXT NOT NULL,namespace TEXT NOT NULL,revision INTEGER NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,namespace,revision),UNIQUE(world,event))");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_shared_expiry_v1(world TEXT NOT NULL,namespace TEXT NOT NULL,due INTEGER NOT NULL,owner TEXT NOT NULL,package_revision INTEGER NOT NULL,canonical TEXT NOT NULL,PRIMARY KEY(world,namespace))");
            s.execute("CREATE INDEX IF NOT EXISTS mineagent_shared_expiry_due_v1 ON mineagent_shared_expiry_v1(world,due)");
            s.execute("CREATE TABLE IF NOT EXISTS mineagent_shared_clock_v1(world TEXT PRIMARY KEY,observed INTEGER NOT NULL)");
        }RetainedRows.initialize(db,world,List.of("mineagent_shared_operations_v1","mineagent_shared_outbox_v1"));return null;});
    }
    public static SharedStateStore open(Path path,UUID world,Authority authority)throws Exception{
        Objects.requireNonNull(world);Objects.requireNonNull(authority);Path p=path.toAbsolutePath().normalize();Files.createDirectories(p.getParent());var db=DriverManager.getConnection("jdbc:sqlite:"+p);
        try{return new SharedStateStore(db,world,authority);}catch(Exception e){try{db.close();}catch(Exception x){e.addSuppressed(x);}throw e;}
    }
    private void authorize(Context c,boolean write,boolean admin){if(closed||!world.equals(c.scope().world())||admin&&c.actorKind().equals("FEEDBACK")||!authority.current(c,write,admin))throw new SecurityException("SHARED_AUTHORITY_REQUIRED");}
    private String id(Scope s){return s.pack()+"/"+s.instance()+"/"+s.namespace();}
    private String address(String key,String subject){return key+"/"+subject;}
    private Namespace owned(Context c)throws Exception{var n=load(c.scope());if(n==null)throw new IllegalStateException("SHARED_NAMESPACE_MISSING");if(!n.owner().equals(c.owner()))throw new SecurityException("SHARED_OWNER_MISMATCH");return n;}
    private String fingerprint(Context c,String method,Object arguments)throws Exception{
        byte[] bytes=SharedJson.JSON.writeValueAsBytes(List.of(c,method,arguments));return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    /** Definition/migration requires the owning package's trusted management context. Existing data is never coerced. */
    public synchronized Receipt define(Context c,UUID operation,long expectedRevision,String schemaJson)throws Exception{
        Objects.requireNonNull(operation);authorize(c,true,true);if(expectedRevision<0||expectedRevision>=SharedJson.SAFE_INTEGER)throw new IllegalArgumentException("SHARED_REVISION");var schema=SharedStateSchema.parse(schemaJson);
        return freshTransaction(c,true,true,now->{authorize(c,true,true);String fp=fingerprint(c,"DEFINE",List.of(expectedRevision,schema));var replay=replay(operation,fp);if(replay!=null){owned(c);authorize(c,true,true);return replay;}
            Namespace old=load(c.scope());if(old!=null&&!old.owner().equals(c.owner()))throw new SecurityException("SHARED_OWNER_MISMATCH");budget("mineagent_shared_operations_v1",8192);
            Receipt result;
            if(old!=null&&old.schema().equals(schema))result=new Receipt("APPLIED",old.revision(),schema.version(),null,"");
            else if((old==null?0:old.revision())!=expectedRevision)result=new Receipt("CONFLICT",old==null?0:old.revision(),old==null?0:old.schema().version(),null,"SHARED_REVISION_CONFLICT");
            else{
                if(old==null){budget("mineagent_shared_namespaces_v1",256);if(schema.version()!=1)throw new IllegalArgumentException("SHARED_INITIAL_SCHEMA_VERSION");}
                else if(schema.version()!=old.schema().version()+1)throw new IllegalArgumentException("SHARED_SCHEMA_VERSION");
                Map<String,Entry> entries=old==null?Map.of():old.records();
                if(old!=null)for(var field:old.schema().fields().entrySet()){var target=schema.fields().get(field.getKey());if(target!=null&&target.ttlSeconds()!=field.getValue().ttlSeconds())throw new IllegalArgumentException("SHARED_TTL_RESET_REQUIRED");}
                for(var entry:entries.values()){var field=schema.fields().get(entry.key());var previous=old.schema().fields().get(entry.key());if(field==null||!field.scope().equals(previous.scope()))throw new IllegalArgumentException("SHARED_MIGRATION_REQUIRES_EXPLICIT_DATA_TRANSFORM");field.validate(entry.value());}
                long revision=old==null?1:Math.addExact(old.revision(),1);var next=new Namespace(c.owner(),revision,schema,entries);
                var changes=schema.fields().keySet().stream().map(key->new Change(key,"*")).toList();UUID event=publish(c,next,changes,true,operation,Provenance.NONE,now);
                result=new Receipt("APPLIED",revision,schema.version(),event,"");
            }
            saveOperation(c,operation,fp,result);authorize(c,true,true);return result;
        });
    }
    /** Does not apply the proposed migration. Due TTL maintenance commits independently; only aggregate counts are projected. */
    public synchronized MigrationPlan planMigration(Context c,long expectedRevision,String schemaJson,String migrationJson)throws Exception{
        authorize(c,false,true);if(expectedRevision<1||expectedRevision>=SharedJson.SAFE_INTEGER)throw new IllegalArgumentException("SHARED_REVISION");
        var schema=SharedStateSchema.parse(schemaJson);var migration=SharedStateMigration.parse(migrationJson);
        return freshTransaction(c,false,true,now->{var old=owned(c);
        String error=migrationConflict(old,expectedRevision,schema,migration);
        if(!error.isEmpty()){authorize(c,false,true);return new MigrationPlan("CONFLICT",old.revision(),old.schema().version(),schema.version(),old.records().size(),old.records().size(),0,0,0,error,old.schema().equals(schema));}
        var next=transform(old,schema,migration,now);authorize(c,false,true);
        return new MigrationPlan("READY",old.revision(),old.schema().version(),schema.version(),old.records().size(),next.namespace().records().size(),next.renamed(),next.dropped(),next.added(),"",false,next.resetTtl());});
    }
    /** Migration, new schema, durable receipt and refetch event are one commit or no commit. */
    public synchronized Receipt migrate(Context c,UUID operation,long expectedRevision,String schemaJson,String migrationJson)throws Exception{
        Objects.requireNonNull(operation);authorize(c,true,true);if(expectedRevision<1||expectedRevision>=SharedJson.SAFE_INTEGER)throw new IllegalArgumentException("SHARED_REVISION");
        var schema=SharedStateSchema.parse(schemaJson);var migration=SharedStateMigration.parse(migrationJson);
        return freshTransaction(c,true,true,now->{
            authorize(c,true,true);var old=owned(c);String fp=fingerprint(c,"MIGRATE",List.of(expectedRevision,schema,migration));
            var replay=replay(operation,fp);if(replay!=null){authorize(c,true,true);return replay;}
            budget("mineagent_shared_operations_v1",8192);String error=migrationConflict(old,expectedRevision,schema,migration);Receipt result;
            if(!error.isEmpty())result=new Receipt("CONFLICT",old.revision(),old.schema().version(),null,error);
            else{
                var next=transform(old,schema,migration,now);var keys=new TreeSet<>(schema.fields().keySet());
                UUID event=publish(c,next.namespace(),keys.stream().map(key->new Change(key,"*")).toList(),true,operation,Provenance.NONE,now);
                result=new Receipt("APPLIED",next.namespace().revision(),schema.version(),event,"");
            }
            saveOperation(c,operation,fp,result);authorize(c,true,true);return result;
        });
    }
    private static String migrationConflict(Namespace old,long expectedRevision,SharedStateSchema schema,SharedStateMigration migration){
        if(old.revision()!=expectedRevision)return "SHARED_REVISION_CONFLICT";
        if(old.schema().version()!=migration.fromSchemaVersion()||schema.version()!=migration.fromSchemaVersion()+1)return "SHARED_SCHEMA_CONFLICT";
        return "";
    }
    private Migrated transform(Namespace old,SharedStateSchema schema,SharedStateMigration migration,long now)throws Exception{
        var records=new TreeMap<>(old.records());var scopes=new HashMap<String,String>();old.schema().fields().forEach((key,field)->scopes.put(key,field.scope()));
        var ttlPolicies=new HashMap<String,Long>();old.schema().fields().forEach((key,field)->ttlPolicies.put(key,field.ttlSeconds()));
        var actors=new TreeSet<String>();int renamed=0,dropped=0,added=0,resetTtl=0;
        for(var mapping:records.entrySet()){
            var entry=mapping.getValue();var field=old.schema().fields().get(entry.key());
            if(field==null||!mapping.getKey().equals(address(entry.key(),entry.subject())))throw new IllegalStateException("SHARED_STORED_RECORD_INVALID");
            validateSubject(field,entry.subject());field.validate(entry.value());if(!entry.subject().isEmpty())actors.add(entry.subject());
        }
        for(var step:migration.steps()){
            if(step.op().equals("RENAME")){
                String prior=scopes.get(step.key());var target=schema.fields().get(step.to());
                if(prior==null||target==null)throw new IllegalArgumentException("SHARED_MIGRATION_FIELD_MISSING");
                if(!prior.equals(target.scope()))throw new IllegalArgumentException("SHARED_MIGRATION_SCOPE_CHANGE_DENIED");
                if(scopes.containsKey(step.to()))throw new IllegalArgumentException("SHARED_MIGRATION_TARGET_EXISTS");
                for(var entry:List.copyOf(records.values()))if(entry.key().equals(step.key())){
                    String destination=address(step.to(),entry.subject());if(records.containsKey(destination))throw new IllegalArgumentException("SHARED_MIGRATION_TARGET_EXISTS");
                    records.remove(address(entry.key(),entry.subject()));records.put(destination,new Entry(step.to(),entry.subject(),entry.value().deepCopy(),entry.expiresAt()));renamed++;
                }
                scopes.remove(step.key());scopes.put(step.to(),prior);ttlPolicies.put(step.to(),ttlPolicies.remove(step.key()));
            }else if(step.op().equals("DROP")){
                if(!migration.confirmDrop()||!scopes.containsKey(step.key()))throw new IllegalArgumentException("SHARED_MIGRATION_DROP_INVALID");
                for(var entry:List.copyOf(records.values()))if(entry.key().equals(step.key())){records.remove(address(entry.key(),entry.subject()));dropped++;}scopes.remove(step.key());ttlPolicies.remove(step.key());
            }else if(step.op().equals("RESET_TTL")){
                var field=schema.fields().get(step.key());
                if(field==null||!scopes.containsKey(step.key()))throw new IllegalArgumentException("SHARED_MIGRATION_FIELD_MISSING");
                if(!field.scope().equals(scopes.get(step.key())))throw new IllegalArgumentException("SHARED_MIGRATION_SCOPE_CHANGE_DENIED");
                long due=deadline(field,now);
                for(var entry:List.copyOf(records.values()))if(entry.key().equals(step.key())){records.put(address(entry.key(),entry.subject()),new Entry(entry.key(),entry.subject(),entry.value(),due));resetTtl++;}
                ttlPolicies.put(step.key(),field.ttlSeconds());
            }else if(Set.of("DEFAULT_SHARED","DEFAULT_ACTORS").contains(step.op())){
                var field=schema.fields().get(step.key());String required=step.op().equals("DEFAULT_SHARED")?"SHARED":"ACTOR";
                if(field==null||!field.scope().equals(required))throw new IllegalArgumentException("SHARED_MIGRATION_DEFAULT_SCOPE");
                if(scopes.containsKey(step.key())&&!scopes.get(step.key()).equals(required))throw new IllegalArgumentException("SHARED_MIGRATION_SCOPE_CHANGE_DENIED");
                field.validate(step.value());scopes.put(step.key(),required);ttlPolicies.putIfAbsent(step.key(),field.ttlSeconds());
                Set<String> subjects=required.equals("SHARED")?Set.of(""):actors;
                for(String subject:subjects)if(!records.containsKey(address(step.key(),subject))){records.put(address(step.key(),subject),new Entry(step.key(),subject,step.value().deepCopy(),deadline(field,now)));added++;}
            }else throw new IllegalArgumentException("SHARED_MIGRATION_STEP_UNSUPPORTED");
            if(records.size()>256)throw new IllegalStateException("SHARED_RECORD_BUDGET");
        }
        for(var policy:ttlPolicies.entrySet()){var target=schema.fields().get(policy.getKey());if(target!=null&&target.ttlSeconds()!=policy.getValue())throw new IllegalArgumentException("SHARED_TTL_RESET_REQUIRED");}
        for(var entry:records.values()){
            var field=schema.fields().get(entry.key());if(field==null)throw new IllegalArgumentException("SHARED_MIGRATION_DROP_REQUIRED");
            validateSubject(field,entry.subject());field.validate(entry.value());
        }
        long revision=Math.addExact(old.revision(),1);if(revision>=SharedJson.SAFE_INTEGER)throw new IllegalStateException("SHARED_REVISION_LIMIT");
        var next=new Namespace(old.owner(),revision,schema,Collections.unmodifiableMap(records));
        if(SharedJson.JSON.writeValueAsBytes(next).length>262144)throw new IllegalStateException("SHARED_NAMESPACE_BYTE_BUDGET");
        return new Migrated(next,renamed,dropped,added,resetTtl);
    }
    private static void validateSubject(SharedStateSchema.Field field,String subject){
        if(field.scope().equals("SHARED")){if(!subject.isEmpty())throw new IllegalArgumentException("SHARED_MIGRATION_SCOPE_CHANGE_DENIED");}
        else{try{if(!UUID.fromString(subject).toString().equals(subject))throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("SHARED_MIGRATION_SCOPE_CHANGE_DENIED");}}
    }
    public synchronized Snapshot read(Context c)throws Exception{
        authorize(c,false,false);return freshTransaction(c,false,false,now->snapshot(c,owned(c),null));
    }
    private Snapshot snapshot(Context c,Namespace n,Set<String> keys){
        var values=new TreeMap<String,JsonNode>();var expires=new TreeMap<String,Long>();
        for(var field:n.schema().fields().entrySet())if((keys==null||keys.contains(field.getKey()))&&field.getValue().canRead(c)){
            var value=n.records().get(address(field.getKey(),field.getValue().subject(c)));
            if(value!=null){values.put(field.getKey(),value.value().deepCopy());if(value.expiresAt()>0)expires.put(field.getKey(),value.expiresAt());}
        }
        authorize(c,false,false);return new Snapshot(n.revision(),n.schema().version(),values,expires);
    }
    public synchronized Receipt transact(Context c,UUID operation,String source)throws Exception{return transact(c,operation,source,Provenance.NONE);}
    public synchronized Receipt transact(Context c,UUID operation,String source,Provenance origin)throws Exception{
        Objects.requireNonNull(operation);Objects.requireNonNull(origin);authorize(c,true,false);var request=SharedStateTransaction.parse(source);
        return freshTransaction(c,true,false,now->{authorize(c,true,false);var n=owned(c);String fp=fingerprint(c,"TRANSACT",origin.equals(Provenance.NONE)?request:List.of(request,origin));var replay=replay(operation,fp);if(replay!=null){authorize(c,true,false);return replay;}
            // Check all declared accesses before evaluating any condition, avoiding private-value oracles.
            for(var condition:request.conditions())requireField(n,c,condition.key(),false);
            for(var write:request.writes()){var field=requireField(n,c,write.key(),true);if(!write.op().equals("DELETE")&&!write.op().equals("ADD"))field.validate(write.value());if(write.op().equals("ADD")&&(!field.type().equals("INTEGER")||!write.value().isIntegralNumber()||!write.value().canConvertToLong()))throw new IllegalArgumentException("SHARED_ADD_INTEGER_REQUIRED");}
            budget("mineagent_shared_operations_v1",8192);String error="";
            if(request.schemaVersion()!=n.schema().version())error="SHARED_SCHEMA_CONFLICT";
            else if(request.expectedRevision()!=null&&request.expectedRevision()!=n.revision())error="SHARED_REVISION_CONFLICT";
            else for(var condition:request.conditions())if(!matches(value(n,c,condition.key()),condition)){error="SHARED_CONDITION_CONFLICT";break;}
            if(error.isEmpty())for(var write:request.writes())if(write.op().equals("PUT_IF_ABSENT")&&value(n,c,write.key())!=null){error="SHARED_CONDITION_CONFLICT";break;}
            Receipt result;
            if(!error.isEmpty())result=new Receipt("CONFLICT",n.revision(),n.schema().version(),null,error);
            else{
                var entries=new TreeMap<>(n.records());var changes=new ArrayList<Change>();
                for(var write:request.writes()){
                    var field=n.schema().fields().get(write.key());String subject=field.subject(c),address=address(write.key(),subject);
                    if(write.op().equals("DELETE"))entries.remove(address);
                    else{JsonNode next=write.value();if(write.op().equals("ADD")){JsonNode prior=value(n,c,write.key());if(prior==null||!prior.isIntegralNumber())throw new IllegalArgumentException("SHARED_ADD_EXISTING_INTEGER_REQUIRED");next=SharedJson.JSON.getNodeFactory().numberNode(Math.addExact(prior.longValue(),next.longValue()));}
                        field.validate(next);entries.put(address,new Entry(write.key(),subject,next.deepCopy(),deadline(field,now)));}
                    changes.add(new Change(write.key(),subject));
                }
                long revision=Math.addExact(n.revision(),1);if(revision>=SharedJson.SAFE_INTEGER)throw new IllegalStateException("SHARED_REVISION_LIMIT");var next=new Namespace(n.owner(),revision,n.schema(),entries);UUID event=publish(c,next,changes,false,operation,origin,now);result=new Receipt("APPLIED",revision,n.schema().version(),event,"");
            }
            saveOperation(c,operation,fp,result);authorize(c,true,false);return result;
        });
    }
    private SharedStateSchema.Field requireField(Namespace n,Context c,String key,boolean write){var f=n.schema().fields().get(key);if(f==null)throw new IllegalArgumentException("SHARED_FIELD_UNDECLARED");if(!f.canRead(c)||write&&!f.canWrite(c))throw new SecurityException("SHARED_FIELD_DENIED");return f;}
    private JsonNode value(Namespace n,Context c,String key){var f=n.schema().fields().get(key);var v=n.records().get(address(key,f.subject(c)));return v==null?null:v.value();}
    public static boolean matches(JsonNode actual,SharedStateTransaction.Condition c){
        if(c.test().equals("ABSENT"))return actual==null;if(c.test().equals("EXISTS"))return actual!=null;if(actual==null)return false;
        if(c.test().equals("EQ"))return actual.equals(c.value());if(!actual.isNumber()||!c.value().isNumber())throw new IllegalArgumentException("SHARED_NUMERIC_CONDITION_REQUIRED");int comparison=actual.decimalValue().compareTo(c.value().decimalValue());return switch(c.test()){case "LT"->comparison<0;case "LTE"->comparison<=0;case "GT"->comparison>0;case "GTE"->comparison>=0;default->false;};
    }
    /** Durable pull outbox: no values or private author IDs leave the store. Consumers refetch an authorized snapshot. */
    public synchronized Feed watch(Context c,long afterRevision)throws Exception{
        authorize(c,false,false);return freshTransaction(c,false,false,now->{var n=owned(c);if(afterRevision<0||afterRevision>n.revision())throw new IllegalArgumentException("SHARED_CURSOR_INVALID");var events=new ArrayList<Notice>();long cursor=afterRevision;
        try(var q=db.prepareStatement("SELECT payload FROM mineagent_shared_outbox_v1 WHERE world=? AND namespace=? AND revision>? AND revision<=? ORDER BY revision LIMIT 16")){
            q.setString(1,world.toString());q.setString(2,id(c.scope()));q.setLong(3,afterRevision);q.setLong(4,n.revision());try(var r=q.executeQuery()){while(r.next()){
                var e=SharedJson.JSON.readValue(r.getString(1),Event.class);cursor=e.revision();var keys=new TreeSet<String>();for(var change:e.changes()){var field=n.schema().fields().get(change.key());if(field!=null&&field.canRead(c)&&(e.schema()||field.subject(c).equals(change.subject())))keys.add(change.key());}
                if(e.schema()||!keys.isEmpty())events.add(new Notice(e.id(),e.revision(),keys,true));
            }}
        }
        authorize(c,false,false);return new Feed(n.revision(),cursor,cursor<n.revision(),events);});
    }
    private UUID publish(Context c,Namespace n,List<Change> changes,boolean schema,UUID operation,Provenance origin,long now)throws Exception{
        return publish(c.scope(),new ExpiryBinding(c.owner(),c.packageRevision(),c.canonical()),c.actor(),c.actorKind(),n,changes,schema,operation,origin,now);
    }
    /** Caller already holds BEGIN IMMEDIATE. TTL maintenance cannot forge an external Context. */
    private UUID publish(Scope scope,ExpiryBinding binding,UUID actor,String actorKind,Namespace n,List<Change> changes,boolean schema,UUID operation,Provenance origin,long now)throws Exception{
        if(!n.owner().equals(binding.owner())||!world.equals(scope.world()))throw new SecurityException("SHARED_EXPIRY_BINDING_MISMATCH");
        if(n.revision()<1||n.revision()>=SharedJson.SAFE_INTEGER)throw new IllegalStateException("SHARED_REVISION_LIMIT");
        if(changes.isEmpty()||changes.size()>64)throw new IllegalStateException("SHARED_CHANGE_BUDGET");
        if(n.records().size()>256)throw new IllegalStateException("SHARED_RECORD_BUDGET");String encoded=SharedJson.JSON.writeValueAsString(n);if(encoded.getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalStateException("SHARED_NAMESPACE_BYTE_BUDGET");budget("mineagent_shared_outbox_v1",8192);
        try(var q=db.prepareStatement("INSERT INTO mineagent_shared_namespaces_v1 VALUES(?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET payload=excluded.payload")){q.setString(1,world.toString());q.setString(2,id(scope));q.setString(3,n.owner().toString());q.setString(4,encoded);q.executeUpdate();}
        UUID event=UUID.nameUUIDFromBytes((world+"|shared|"+id(scope)+"|"+n.revision()).getBytes(StandardCharsets.UTF_8));var data=new Event(event,n.revision(),schema,actor,actorKind,binding.packageRevision(),binding.canonical(),changes,operation,now,origin);
        try(var q=db.prepareStatement("INSERT INTO mineagent_shared_outbox_v1(world,namespace,revision,event,payload) VALUES(?,?,?,?,?)")){q.setString(1,world.toString());q.setString(2,id(scope));q.setLong(3,n.revision());q.setString(4,event.toString());q.setString(5,SharedJson.JSON.writeValueAsString(data));q.executeUpdate();}
        indexExpiry(scope,n,binding);return event;
    }
    public synchronized Snapshot read(Context c,Set<String> keys)throws Exception{
        authorize(c,false,false);if(keys==null||keys.isEmpty()||keys.size()>8)throw new IllegalArgumentException("SHARED_READ_KEYS");var selected=Set.copyOf(keys);
        return freshTransaction(c,false,false,now->{var n=owned(c);for(String key:selected){SharedJson.name(key);requireField(n,c,key,false);}return snapshot(c,n,selected);});
    }
    /** Metadata only; safe inside another store's SQL callback. Does not read values or promise TTL catch-up. */
    public synchronized Description describe(Context c)throws Exception{authorize(c,false,false);var n=owned(c);var fields=new TreeMap<String,SharedStateSchema.Field>();n.schema().fields().forEach((key,field)->{if(field.canRead(c))fields.put(key,field);});authorize(c,false,false);return new Description(n.revision(),n.schema().version(),fields);}
    /** Internal metadata only. Not an external read grant; Native callers must authorize before exposing any fields. */
    public synchronized RuntimeSchema schemaForRuntime(Scope scope)throws Exception{if(!scope.world().equals(world))throw new SecurityException("SHARED_WORLD");var n=load(scope);if(n==null)throw new IllegalStateException("SHARED_NAMESPACE_MISSING");return new RuntimeSchema(n.owner(),n.schema().version(),n.schema().fields());}
    public synchronized List<String> namespacesForRuntime(UUID pack,UUID instance)throws Exception{var names=new ArrayList<String>();String prefix=pack+"/"+instance+"/";try(var q=db.prepareStatement("SELECT id FROM mineagent_shared_namespaces_v1 WHERE world=? AND id LIKE ? ORDER BY id LIMIT 256")){q.setString(1,world.toString());q.setString(2,prefix+"%");try(var rows=q.executeQuery()){while(rows.next())names.add(rows.getString(1).substring(prefix.length()));}}return List.copyOf(names);}
    /** Runtime export boundary only: immutable commit metadata, never field values. */
    public synchronized List<Export> pendingExports(int limit)throws Exception{
        if(limit<1||limit>64||closed)throw new IllegalArgumentException("SHARED_EXPORT_LIMIT");var result=new ArrayList<Export>();
        try(var q=db.prepareStatement("SELECT o.namespace,o.payload FROM mineagent_shared_outbox_v1 o LEFT JOIN mineagent_shared_event_exports_v1 e ON e.world=o.world AND e.event=o.event WHERE o.world=? AND o.archived=0 AND e.event IS NULL ORDER BY o.rowid LIMIT ?")){q.setString(1,world.toString());q.setInt(2,limit);try(var rows=q.executeQuery()){while(rows.next()){String[] key=rows.getString(1).split("/",-1);if(key.length!=3)throw new IllegalStateException("SHARED_EXPORT_SCOPE");String payload=rows.getString(2);var event=SharedJson.JSON.readValue(payload,Event.class);result.add(new Export(new Scope(world,UUID.fromString(key[0]),UUID.fromString(key[1]),key[2]),hash(payload),event));}}}return List.copyOf(result);
    }
    public synchronized void acknowledgeExport(Export row)throws Exception{if(!row.scope().world().equals(world)||closed)throw new SecurityException("SHARED_EXPORT_SCOPE");transaction(()->{try(var q=db.prepareStatement("SELECT namespace,payload FROM mineagent_shared_outbox_v1 WHERE world=? AND event=?")){q.setString(1,world.toString());q.setString(2,row.event().id().toString());try(var data=q.executeQuery()){if(!data.next()||!data.getString(1).equals(id(row.scope()))||!hash(data.getString(2)).equals(row.payloadHash()))throw new IllegalStateException("SHARED_EXPORT_CHANGED");}}try(var q=db.prepareStatement("INSERT INTO mineagent_shared_event_exports_v1 VALUES(?,?,?) ON CONFLICT(world,event) DO NOTHING")){q.setString(1,world.toString());q.setString(2,row.event().id().toString());q.setString(3,row.payloadHash());q.executeUpdate();}return null;});}
    private String hash(String value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private Namespace load(Scope s)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM mineagent_shared_namespaces_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id(s));try(var r=q.executeQuery()){return r.next()?SharedJson.JSON.readValue(r.getString(1),Namespace.class):null;}}}
    private Receipt replay(UUID operation,String fingerprint)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM mineagent_shared_operations_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(!r.next())return null;var op=SharedJson.JSON.readValue(r.getString(1),Operation.class);if(!op.fingerprint().equals(fingerprint))throw new IllegalArgumentException("SHARED_OPERATION_REUSED");return op.receipt();}}}
    /** Trusted verifier reads an existing transaction result without executing or replaying it. */
    public synchronized Optional<Receipt> transactionReceipt(Context context,UUID operation,String transactionJson,Provenance origin)throws Exception{
        Objects.requireNonNull(operation);Objects.requireNonNull(origin);authorize(context,false,false);var request=SharedStateTransaction.parse(transactionJson);
        String expected=fingerprint(context,"TRANSACT",origin.equals(Provenance.NONE)?request:List.of(request,origin));
        try(var q=db.prepareStatement("SELECT payload FROM mineagent_shared_operations_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){
            if(!r.next()){authorize(context,false,false);return Optional.empty();}
            var value=SharedJson.JSON.readValue(r.getString(1),Operation.class);if(!value.context().equals(context))throw new SecurityException("SHARED_RECEIPT_SCOPE");
            if(!value.fingerprint().equals(expected))throw new IllegalArgumentException("SHARED_RECEIPT_PLAN_MISMATCH");authorize(context,false,false);return Optional.of(value.receipt());
        }}
    }
    public synchronized Receipt receipt(Context context,UUID operation)throws Exception{
        authorize(context,false,false);try(var q=db.prepareStatement("SELECT payload FROM mineagent_shared_operations_v1 WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("SHARED_RECEIPT_MISSING");var value=SharedJson.JSON.readValue(r.getString(1),Operation.class);if(!value.context().equals(context))throw new SecurityException("SHARED_RECEIPT_SCOPE");authorize(context,false,false);return value.receipt();}}
    }
    private static long deadline(SharedStateSchema.Field field,long now){
        if(field.ttlSeconds()==0)return 0;long due=Math.addExact(now,Math.multiplyExact(field.ttlSeconds(),1000));
        if(due>=SharedJson.SAFE_INTEGER)throw new IllegalStateException("SHARED_CLOCK_LIMIT");return due;
    }
    /** Persisted high-water plus monotonic elapsed time. Repeated calls do not discard sub-ms elapsed time. */
    private long effectiveNow()throws Exception{
        long nano=System.nanoTime(),wall=System.currentTimeMillis();
        if(clockBase==0){clockBase=Math.max(1,wall);clockNano=nano;}
        long predicted=Math.addExact(clockBase,Math.max(0,(nano-clockNano)/1_000_000));long high=0;
        try(var q=db.prepareStatement("SELECT observed FROM mineagent_shared_clock_v1 WHERE world=?")){q.setString(1,world.toString());try(var rows=q.executeQuery()){if(rows.next())high=rows.getLong(1);}}
        long now=Math.max(predicted,Math.max(wall,high));if(now<1||now>=SharedJson.SAFE_INTEGER)throw new IllegalStateException("SHARED_CLOCK_LIMIT");
        if(now>predicted){clockBase=now;clockNano=nano;}
        try(var q=db.prepareStatement("INSERT INTO mineagent_shared_clock_v1 VALUES(?,?) ON CONFLICT(world) DO UPDATE SET observed=MAX(observed,excluded.observed)")){q.setString(1,world.toString());q.setLong(2,now);q.executeUpdate();}return now;
    }
    private void indexExpiry(Scope scope,Namespace n,ExpiryBinding binding)throws Exception{
        long due=n.records().values().stream().mapToLong(Entry::expiresAt).filter(v->v>0).min().orElse(0);
        if(due==0){try(var q=db.prepareStatement("DELETE FROM mineagent_shared_expiry_v1 WHERE world=? AND namespace=?")){q.setString(1,world.toString());q.setString(2,id(scope));q.executeUpdate();}return;}
        try(var q=db.prepareStatement("INSERT INTO mineagent_shared_expiry_v1 VALUES(?,?,?,?,?,?) ON CONFLICT(world,namespace) DO UPDATE SET due=excluded.due,owner=excluded.owner,package_revision=excluded.package_revision,canonical=excluded.canonical")){
            q.setString(1,world.toString());q.setString(2,id(scope));q.setLong(3,due);q.setString(4,binding.owner().toString());q.setLong(5,binding.packageRevision());q.setString(6,binding.canonical());q.executeUpdate();
        }
    }
    private ExpiryBinding savedExpiryBinding(Scope scope)throws Exception{
        try(var q=db.prepareStatement("SELECT owner,package_revision,canonical FROM mineagent_shared_expiry_v1 WHERE world=? AND namespace=?")){q.setString(1,world.toString());q.setString(2,id(scope));try(var rows=q.executeQuery()){return rows.next()?new ExpiryBinding(UUID.fromString(rows.getString(1)),rows.getLong(2),rows.getString(3)):null;}}
    }
    /** Entire namespace catch-up is one independent commit; each durable event has at most 64 changes. */
    private int expire(Scope scope,Namespace n,ExpiryBinding binding,long now)throws Exception{
        if(!n.owner().equals(binding.owner()))throw new SecurityException("SHARED_EXPIRY_BINDING_MISMATCH");
        var expired=n.records().entrySet().stream().filter(e->e.getValue().expiresAt()>0&&e.getValue().expiresAt()<=now).sorted(Map.Entry.comparingByKey()).toList();
        if(expired.isEmpty()){indexExpiry(scope,n,binding);return 0;}
        var records=new TreeMap<>(n.records());long revision=n.revision();
        UUID actor=UUID.nameUUIDFromBytes((world+"|shared-system-expiry-v1").getBytes(StandardCharsets.UTF_8));
        for(int offset=0;offset<expired.size();offset+=64){var changes=new ArrayList<Change>();
            for(var item:expired.subList(offset,Math.min(offset+64,expired.size()))){var entry=item.getValue();records.remove(item.getKey());changes.add(new Change(entry.key(),entry.subject()));}
            revision=Math.addExact(revision,1);var next=new Namespace(n.owner(),revision,n.schema(),records);
            UUID operation=UUID.nameUUIDFromBytes((world+"|shared-expiry|"+id(scope)+"|"+revision).getBytes(StandardCharsets.UTF_8));
            publish(scope,binding,actor,"SYSTEM_EXPIRY",next,changes,false,operation,Provenance.NONE,now);
        }
        return expired.size();
    }
    @FunctionalInterface private interface TimedWork<T>{T run(long now)throws Exception;}
    private record Fresh<T>(boolean cleaned,T value){}
    /** Expiry commits first, then the requested operation gets a fresh lock/time. Failed user writes never own expiry changes. */
    private <T>T freshTransaction(Context c,boolean write,boolean admin,TimedWork<T> work)throws Exception{
        for(int attempt=0;attempt<8;attempt++){
            Fresh<T> result=transaction(()->{authorize(c,write,admin);long now=effectiveNow();var n=load(c.scope());
                if(n!=null){if(!n.owner().equals(c.owner()))throw new SecurityException("SHARED_OWNER_MISMATCH");
                    if(expire(c.scope(),n,new ExpiryBinding(c.owner(),c.packageRevision(),c.canonical()),now)>0){authorize(c,write,admin);return new Fresh<T>(true,null);}}
                T value=work.run(now);authorize(c,write,admin);return new Fresh<T>(false,value);
            });
            if(!result.cleaned())return result.value();
        }
        throw new IllegalStateException("SHARED_EXPIRY_BUSY");
    }
    private Scope storedScope(String namespace){String[] parts=namespace.split("/",-1);if(parts.length!=3)throw new IllegalStateException("SHARED_EXPIRY_SCOPE");return new Scope(world,UUID.fromString(parts[0]),UUID.fromString(parts[1]),parts[2]);}
    private List<Scope> dueScopes(long now,int limit)throws Exception{
        var scopes=new ArrayList<Scope>();try(var q=db.prepareStatement("SELECT namespace FROM mineagent_shared_expiry_v1 WHERE world=? AND due<=? ORDER BY due,namespace LIMIT ?")){q.setString(1,world.toString());q.setLong(2,now);q.setInt(3,limit);try(var rows=q.executeQuery()){while(rows.next())scopes.add(storedScope(rows.getString(1)));}}return List.copyOf(scopes);
    }
    /** Trusted timer only. A stopped package does not stop its previously declared data expiry policy. */
    public synchronized ExpirySweep expireDue(int limit,ExpiryResolver resolver)throws Exception{
        if(closed||limit<1||limit>16)throw new IllegalArgumentException("SHARED_EXPIRY_LIMIT");Objects.requireNonNull(resolver);
        var due=transaction(()->dueScopes(effectiveNow(),limit));int records=0;
        for(var scope:due){var current=resolver.current(scope);records+=transaction(()->{
            long now=effectiveNow();var n=load(scope);var saved=savedExpiryBinding(scope);
            if(n==null)throw new IllegalStateException("SHARED_EXPIRY_NAMESPACE_MISSING");
            if(saved==null)return 0;var binding=current==null?saved:current;return expire(scope,n,binding,now);
        });}
        boolean more=transaction(()->!dueScopes(effectiveNow(),1).isEmpty());return new ExpirySweep(due.size(),records,more);
    }
    private void saveOperation(Context c,UUID id,String fingerprint,Receipt receipt)throws Exception{try(var q=db.prepareStatement("INSERT INTO mineagent_shared_operations_v1(world,id,payload) VALUES(?,?,?)")){q.setString(1,world.toString());q.setString(2,id.toString());q.setString(3,SharedJson.JSON.writeValueAsString(new Operation(c,fingerprint,receipt)));q.executeUpdate();}}
    private void budget(String table,int maximum)throws Exception{
        if(Set.of("mineagent_shared_operations_v1","mineagent_shared_outbox_v1").contains(table)){
            RetainedRows.requireCapacity(db,world,table);if(RetainedRows.usage(db,world,table).hot()>=maximum){var rows=new ArrayList<Long>();
                if(table.equals("mineagent_shared_operations_v1")){try(var query=db.prepareStatement("SELECT rowid FROM mineagent_shared_operations_v1 WHERE world=? AND archived=0 ORDER BY rowid LIMIT 256")){query.setString(1,world.toString());try(var result=query.executeQuery()){while(result.next())rows.add(result.getLong(1));}}}
                else{try(var query=db.prepareStatement("SELECT o.rowid,o.payload,e.payload_hash FROM mineagent_shared_outbox_v1 o JOIN mineagent_shared_event_exports_v1 e ON e.world=o.world AND e.event=o.event WHERE o.world=? AND o.archived=0 ORDER BY o.rowid LIMIT 256")){query.setString(1,world.toString());try(var result=query.executeQuery()){while(result.next()){if(!hash(result.getString(2)).equals(result.getString(3)))throw new IllegalStateException("SHARED_EXPORT_CHANGED");rows.add(result.getLong(1));}}}}
                RetainedRows.archive(db,world,table,rows);
            }
            if(RetainedRows.usage(db,world,table).hot()>=maximum)throw new IllegalStateException("SHARED_STORAGE_BUDGET");return;
        }
        try(var query=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE world=?")){query.setString(1,world.toString());try(var result=query.executeQuery()){if(result.next()&&result.getInt(1)>=maximum)throw new IllegalStateException("SHARED_STORAGE_BUDGET");}}
    }
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T transaction(Work<T> work)throws Exception{try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");try{T result=work.run();s.execute("COMMIT");return result;}catch(Exception|Error e){try{s.execute("ROLLBACK");}catch(Exception x){e.addSuppressed(x);}throw e;}}}
    @Override public synchronized void close()throws Exception{closed=true;db.close();}
}
