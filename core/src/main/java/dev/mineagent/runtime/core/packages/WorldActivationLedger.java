package dev.mineagent.runtime.core.packages;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeInstanceLocation;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.util.*;
/** Intent is persisted before native JS is entered. Unknown external side effects are never replayed automatically. */
public final class WorldActivationLedger implements AutoCloseable {
    public record Activation(UUID operationId,UUID worldId,UUID owner,UUID packageId,long packageRevision,String canonicalSha256,
            UUID definitionId,UUID instanceId,RuntimeInstanceLocation location,String state,String error,long revision,boolean autoRestore,String ownerName,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String restoreBlock,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ResumeInput resumeInput){
        public Activation(UUID operationId,UUID worldId,UUID owner,UUID packageId,long packageRevision,String canonicalSha256,UUID definitionId,UUID instanceId,RuntimeInstanceLocation location,String state,String error,long revision,boolean autoRestore,String ownerName){this(operationId,worldId,owner,packageId,packageRevision,canonicalSha256,definitionId,instanceId,location,state,error,revision,autoRestore,ownerName,null,null);}
        public Activation{
            if(restoreBlock!=null&&(!state.equals("INTERRUPTED")||!Set.of("BEFORE_RESTORE","COMPATIBILITY_UNLOADED").contains(restoreBlock)))throw new IllegalArgumentException("RESTORE_BLOCK_CONTEXT");
            if(resumeInput!=null&&(!Set.of("RESTORE_PENDING","RESTORING").contains(state)||!resumeInput.owner().equals(owner)||!resumeInput.activation().equals(operationId)||!resumeInput.instance().equals(instanceId)||!resumeInput.hash().equals(canonicalSha256)))throw new IllegalArgumentException("RESTORE_REQUEST_CONTEXT");
            ownerName=ownerName==null?"":ownerName;if(ownerName.length()>64||autoRestore&&ownerName.isBlank())throw new IllegalArgumentException("RESTORE_OWNER_NAME");}
    }
    public record ResumeInput(UUID operation,UUID owner,UUID activation,UUID instance,String hash,long activationRevision,long packageRevision,long instanceRevision,String environment,long runGeneration,long manageGeneration){
        public ResumeInput{Objects.requireNonNull(operation);Objects.requireNonNull(owner);Objects.requireNonNull(activation);Objects.requireNonNull(instance);if(operation.equals(activation)||hash==null||!hash.matches("[a-f0-9]{64}")||environment==null||!environment.matches("[a-f0-9]{64}")||activationRevision<1||packageRevision<1||instanceRevision<1||runGeneration<0||manageGeneration<0)throw new IllegalArgumentException("RESTORE_REQUEST_INVALID");}
    }
    public record ResumeReceipt(ResumeInput input,long queuedRevision){}
    public record ResumeResult(ResumeReceipt receipt,Activation current,boolean duplicate){}
    public static final Set<String> RESUME_ERRORS=Set.of("RESTORE_PREPARATION_FAILED","RESTORE_REQUEST_INVALID","RESTORE_REQUEST_CONTEXT","RESTORE_CONFIRM_REQUIRED","RESTORE_SCOPE_DENIED","RESTORE_NOT_ELIGIBLE","RESTORE_REQUEST_REUSED","RESTORE_REQUEST_BUDGET","RESTORE_REQUEST_STALE","RESTORE_CONFIRMATION_EXPIRED","RESTORE_ENVIRONMENT_CHANGED","RESTORE_INSTANCE_CHANGED","RESTORE_CHUNK_UNLOADED","RESTORE_HOST_PRESENT","RESTORE_PERMISSION_DENIED","RESTORE_PACKAGE_UNAVAILABLE","RESTORE_SOURCE_CHANGED","RESTORE_INSTANCE_CONTEXT","RESTORE_CONTRACT_MISSING","RESTORE_REGISTRATION_REQUIRED","INSTANCE_MOVE_RECOVERY_REQUIRED","ACTIVATION_REQUIRES_LIFECYCLE");
    private static final String RESUME_NAMESPACE="world_package_restore_requests_v1";
    private static final String NAMESPACE="world_package_activations_v1";
    private final SqliteRuntimeRepository repo;private final UUID world;private final ObjectMapper json=new ObjectMapper();
    private final Map<UUID,Activation> records=new LinkedHashMap<>();
    private WorldActivationLedger(Path db,UUID world)throws Exception{
        this.world=world;repo=new SqliteRuntimeRepository(db);
        try{for(var row:repo.list(world,NAMESPACE)){var a=json.readValue(row.payload(),Activation.class);if(!a.worldId().equals(world)||!a.operationId().toString().equals(row.recordId())||a.revision()!=row.revision())throw new IllegalStateException("ACTIVATION_RECORD_CONTEXT");records.put(a.operationId(),a);}
            for(var a:List.copyOf(records.values())){
                if(a.state().equals("ACTIVE")&&a.autoRestore())finish(a.operationId(),"RESTORE_PENDING","RESTORE_SCHEDULED");
                else if(Set.of("PREPARING","ACTIVE","RESTORING").contains(a.state()))finish(a.operationId(),"INTERRUPTED","SERVER_RESTARTED_NO_REPLAY");
            }
        }catch(Exception e){repo.close();throw e;}
    }
    public static WorldActivationLedger open(Path db,UUID world)throws Exception{return new WorldActivationLedger(db,Objects.requireNonNull(world));}
    public synchronized Optional<Activation> get(UUID owner,UUID operation){var a=records.get(operation);if(a!=null&&!a.owner().equals(owner))throw new SecurityException("ACTIVATION_OWNER");return Optional.ofNullable(a);}
    public synchronized List<Activation> all(){return List.copyOf(records.values());}
    public synchronized Activation prepare(UUID owner,UUID operation,UUID pkg,long revision,String hash,UUID definition,RuntimeInstanceLocation location,boolean authorized)throws Exception{
        return prepare(owner,operation,pkg,revision,hash,definition,location,authorized,false,"");
    }
    public synchronized Activation prepare(UUID owner,UUID operation,UUID pkg,long revision,String hash,UUID definition,RuntimeInstanceLocation location,boolean authorized,boolean autoRestore,String ownerName)throws Exception{
        if(!authorized)throw new SecurityException("NATIVE_ACTIVATION_DENIED");
        Objects.requireNonNull(owner);Objects.requireNonNull(operation);Objects.requireNonNull(pkg);Objects.requireNonNull(definition);Objects.requireNonNull(location);
        if(revision<1||hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("ACTIVATION_CONTEXT");
        var old=get(owner,operation).orElse(null);
        if(old!=null){if(!old.packageId().equals(pkg)||old.packageRevision()!=revision||!old.canonicalSha256().equals(hash)||!old.definitionId().equals(definition)||!old.location().equals(location)||old.autoRestore()!=autoRestore)throw new IllegalArgumentException("OPERATION_ID_REUSED");return old;}
        if(repo.getIncludingDeleted(world,RESUME_NAMESPACE,operation.toString()).isPresent())throw new IllegalArgumentException("OPERATION_ID_REUSED");
        if(records.size()>=4096)throw new IllegalStateException("ACTIVATION_LEDGER_FULL");
        var next=new Activation(operation,world,owner,pkg,revision,hash,definition,UUID.randomUUID(),location,"PREPARING","",1,autoRestore,ownerName);save(next,0);return next;
    }
    public synchronized Activation beginRestore(UUID operation)throws Exception{
        var a=Objects.requireNonNull(records.get(operation));if(!a.autoRestore()||!a.state().equals("RESTORE_PENDING"))throw new IllegalStateException("RESTORE_NOT_PENDING");
        return finish(operation,"RESTORING","");
    }
    public synchronized Activation revokeRestore(UUID owner,UUID operation,long expectedRevision)throws Exception{
        var a=get(owner,operation).orElseThrow();if(a.revision()!=expectedRevision)throw new IllegalStateException("STALE_ACTIVATION");if(!a.autoRestore())return a;
        boolean pending=a.state().equals("RESTORE_PENDING");
        var next=new Activation(a.operationId(),world,a.owner(),a.packageId(),a.packageRevision(),a.canonicalSha256(),a.definitionId(),a.instanceId(),a.location(),pending?"INTERRUPTED":a.state(),pending?"AUTO_RESTORE_REVOKED":a.error(),a.revision()+1,false,a.ownerName(),pending?null:a.restoreBlock(),pending?null:a.resumeInput());save(next,a.revision());return next;
    }
    public synchronized Activation finish(UUID operation,String state,String error)throws Exception{
        if(!Set.of("ACTIVE","FAILED","INTERRUPTED","DISABLED","RESTORE_PENDING","RESTORING").contains(state)||error==null||!error.matches("[A-Z0-9_]{0,80}"))throw new IllegalArgumentException("ACTIVATION_STATE");
        var a=Objects.requireNonNull(records.get(operation));if(a.state().equals(state)&&a.error().equals(error))return a;
        if(!Set.of("PREPARING","ACTIVE","RESTORE_PENDING","RESTORING").contains(a.state()))throw new IllegalStateException("ACTIVATION_TERMINAL");
        if(state.equals("RESTORE_PENDING")&&(!a.autoRestore()||!a.state().equals("ACTIVE"))||state.equals("RESTORING")&&(!a.autoRestore()||!a.state().equals("RESTORE_PENDING"))||state.equals("ACTIVE")&&a.state().equals("RESTORE_PENDING"))throw new IllegalStateException("RESTORE_TRANSITION");
        var next=new Activation(a.operationId(),world,a.owner(),a.packageId(),a.packageRevision(),a.canonicalSha256(),a.definitionId(),a.instanceId(),a.location(),state,error,a.revision()+1,a.autoRestore(),a.ownerName(),null,Set.of("RESTORE_PENDING","RESTORING").contains(state)?a.resumeInput():null);save(next,a.revision());return next;
    }
    /** Called only after a known pre-load rejection or successful managed unload; old errors are not backfilled. */
    public synchronized Activation interruptRestorable(UUID operation,String error,String origin)throws Exception{
        var a=Objects.requireNonNull(records.get(operation));
        if(error==null||!error.matches("[A-Z0-9_]{1,80}")||!(origin.equals("BEFORE_RESTORE")&&a.state().equals("RESTORE_PENDING")||origin.equals("COMPATIBILITY_UNLOADED")&&a.state().equals("ACTIVE")&&NativeCompatibilityPolicy.ERROR_CODES.contains(error)))throw new IllegalStateException("RESTORE_BLOCK_CONTEXT");
        var next=new Activation(a.operationId(),world,a.owner(),a.packageId(),a.packageRevision(),a.canonicalSha256(),a.definitionId(),a.instanceId(),a.location(),"INTERRUPTED",error,a.revision()+1,a.autoRestore(),a.ownerName(),origin,null);save(next,a.revision());return next;
    }
    public synchronized Optional<ResumeResult> replayResume(ResumeInput input)throws Exception{
        if(records.containsKey(input.operation()))throw new IllegalStateException("RESTORE_REQUEST_REUSED");
        var row=repo.getIncludingDeleted(world,RESUME_NAMESPACE,input.operation().toString()).orElse(null);if(row==null)return Optional.empty();
        var receipt=json.readValue(row.payload(),ResumeReceipt.class);if(row.deleted()||!receipt.input().equals(input))throw new IllegalStateException("RESTORE_REQUEST_REUSED");
        return Optional.of(new ResumeResult(receipt,get(input.owner(),input.activation()).orElseThrow(),true));
    }
    public synchronized ResumeResult resume(ResumeInput input,boolean authorized)throws Exception{
        if(!authorized)throw new SecurityException("RESTORE_SCOPE_DENIED");var replay=replayResume(input);if(replay.isPresent())return replay.get();
        var a=get(input.owner(),input.activation()).orElseThrow();if(!WorldRestorePolicy.resumeEligible(a))throw new IllegalStateException("RESTORE_NOT_ELIGIBLE");
        if(a.revision()!=input.activationRevision()||!a.instanceId().equals(input.instance())||!a.canonicalSha256().equals(input.hash()))throw new IllegalStateException("RESTORE_REQUEST_STALE");
        var next=new Activation(a.operationId(),world,a.owner(),a.packageId(),a.packageRevision(),a.canonicalSha256(),a.definitionId(),a.instanceId(),a.location(),"RESTORE_PENDING","EXPLICIT_RESTORE_QUEUED",a.revision()+1,a.autoRestore(),a.ownerName(),null,input);
        var receipt=new ResumeReceipt(input,next.revision());repo.worldActivationResume(world,a.operationId(),a.revision(),json.writeValueAsString(next),input.operation(),json.writeValueAsString(receipt),System.currentTimeMillis());records.put(a.operationId(),next);return new ResumeResult(receipt,next,false);
    }
    private void save(Activation a,long expected)throws Exception{if(!repo.compareAndSet(world,NAMESPACE,a.operationId().toString(),expected,json.writeValueAsString(a),System.currentTimeMillis()).accepted())throw new IllegalStateException("ACTIVATION_CAS");records.put(a.operationId(),a);}
    @Override public synchronized void close()throws Exception{repo.close();}
}
