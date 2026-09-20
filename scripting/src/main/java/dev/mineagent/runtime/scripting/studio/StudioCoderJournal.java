package dev.mineagent.runtime.scripting.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy;
import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.core.packages.CodeDraftSources;
import dev.mineagent.runtime.core.packages.JavaDependencyGraph;
import dev.mineagent.runtime.core.packages.JavaDependencyApi;
import dev.mineagent.runtime.core.packages.ScriptDependencySources;
import dev.mineagent.runtime.core.compile.NativeCoderContext;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Durable Coder suggestions; no model calls, compilation, draft mutation or Native execution here. */
public final class StudioCoderJournal {
    public static final int MAX_RAW_BYTES=24*1024*1024;
    private static final long RAW_BUDGET=256L*1024*1024, META_BUDGET=128L*1024*1024;
    private static final int RECORD_RESERVE=768*1024, ATTEMPT_RESERVE=160*1024;
    private static final String NS="studio_coder_jobs_v1";
    private static final Set<String> ACTIVE=Set.of("PENDING","GENERATING","VALIDATING","READY","ADOPTING");
    private static final Set<String> STATES=Set.of("PENDING","GENERATING","VALIDATING","READY","ADOPTING","ADOPTED","FAILED","STALE","CANCELLED","INTERRUPTED");
    public record Repair(UUID job,long revision,String previousHash,boolean raw,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String entry,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) Map<String,CodeDraft.SourceRef> additionalSources){
        public Repair(UUID job,long revision,String previousHash,boolean raw){this(job,revision,previousHash,raw,"",Map.of());}
        public Repair{Objects.requireNonNull(job);entry=entry==null?"":entry;additionalSources=additionalSources==null?Map.of():Map.copyOf(additionalSources);if(revision<1||!hash(previousHash,false)||additionalSources.size()>63||raw&&!additionalSources.isEmpty())throw new IllegalArgumentException("STUDIO_CODER_REPAIR_SOURCE");if(!entry.isEmpty())CodeDraftSources.path(entry);}
    }
    public record Input(UUID operation,UUID world,UUID owner,UUID agent,UUID task,long taskRevision,long taskIntent,
            UUID packageId,long packageRevision,String packageHash,UUID baseDraft,long baseRevision,
            String sourceHash,int sourceBytes,String path,String prompt,int maxAttempts,String diagnostics,
            long configRevision,NativeCompatibilityPolicy.Environment environment,String hostApi,long agentAuthorityGeneration,Repair repair,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean workspace,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) Map<String,CodeDraft.SourceRef> additionalSources,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JavaDependencyGraph javaDependencies,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean dependencyDeclaration,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ScriptDependencySources scriptDependencies,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean shareScriptDependencySources,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) NativeCoderContext nativeSelection,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean shareNativeContext) {
        public Input(UUID operation,UUID world,UUID owner,UUID agent,UUID task,long taskRevision,long taskIntent,UUID packageId,long packageRevision,String packageHash,UUID baseDraft,long baseRevision,String sourceHash,int sourceBytes,String path,String prompt,int maxAttempts,String diagnostics,long configRevision,NativeCompatibilityPolicy.Environment environment,String hostApi,long agentAuthorityGeneration,Repair repair,boolean workspace,Map<String,CodeDraft.SourceRef> additionalSources,JavaDependencyGraph javaDependencies,boolean dependencyDeclaration,ScriptDependencySources scriptDependencies,boolean shareScriptDependencySources){this(operation,world,owner,agent,task,taskRevision,taskIntent,packageId,packageRevision,packageHash,baseDraft,baseRevision,sourceHash,sourceBytes,path,prompt,maxAttempts,diagnostics,configRevision,environment,hostApi,agentAuthorityGeneration,repair,workspace,additionalSources,javaDependencies,dependencyDeclaration,scriptDependencies,shareScriptDependencySources,null,false);}
        public Input(UUID operation,UUID world,UUID owner,UUID agent,UUID task,long taskRevision,long taskIntent,UUID packageId,long packageRevision,String packageHash,UUID baseDraft,long baseRevision,String sourceHash,int sourceBytes,String path,String prompt,int maxAttempts,String diagnostics,long configRevision,NativeCompatibilityPolicy.Environment environment,String hostApi,long agentAuthorityGeneration,Repair repair,boolean workspace,Map<String,CodeDraft.SourceRef> additionalSources,JavaDependencyGraph javaDependencies,boolean dependencyDeclaration){this(operation,world,owner,agent,task,taskRevision,taskIntent,packageId,packageRevision,packageHash,baseDraft,baseRevision,sourceHash,sourceBytes,path,prompt,maxAttempts,diagnostics,configRevision,environment,hostApi,agentAuthorityGeneration,repair,workspace,additionalSources,javaDependencies,dependencyDeclaration,null,false);}
        public Input(UUID operation,UUID world,UUID owner,UUID agent,UUID task,long taskRevision,long taskIntent,UUID packageId,long packageRevision,String packageHash,UUID baseDraft,long baseRevision,String sourceHash,int sourceBytes,String path,String prompt,int maxAttempts,String diagnostics,long configRevision,NativeCompatibilityPolicy.Environment environment,String hostApi,long agentAuthorityGeneration,Repair repair,boolean workspace,Map<String,CodeDraft.SourceRef> additionalSources){this(operation,world,owner,agent,task,taskRevision,taskIntent,packageId,packageRevision,packageHash,baseDraft,baseRevision,sourceHash,sourceBytes,path,prompt,maxAttempts,diagnostics,configRevision,environment,hostApi,agentAuthorityGeneration,repair,workspace,additionalSources,null,false);}
        public Input(UUID operation,UUID world,UUID owner,UUID agent,UUID task,long taskRevision,long taskIntent,UUID packageId,long packageRevision,String packageHash,UUID baseDraft,long baseRevision,String sourceHash,int sourceBytes,String path,String prompt,int maxAttempts,String diagnostics,long configRevision,NativeCompatibilityPolicy.Environment environment,String hostApi,long agentAuthorityGeneration,Repair repair){this(operation,world,owner,agent,task,taskRevision,taskIntent,packageId,packageRevision,packageHash,baseDraft,baseRevision,sourceHash,sourceBytes,path,prompt,maxAttempts,diagnostics,configRevision,environment,hostApi,agentAuthorityGeneration,repair,false,Map.of());}
        public Input {
            Objects.requireNonNull(operation);Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);Objects.requireNonNull(task);Objects.requireNonNull(packageId);Objects.requireNonNull(environment);
            if(taskRevision<1||taskIntent<1||packageRevision<0||baseRevision<0||sourceBytes<0||sourceBytes>64000||maxAttempts<1||maxAttempts>3||configRevision<0||agentAuthorityGeneration<0
                    ||!hash(packageHash,true)||!hash(sourceHash,false)||path==null||!path.matches("[A-Za-z0-9_./-]{1,128}")||path.contains("..")
                    ||!path.toLowerCase(Locale.ROOT).matches(".*\\.(java|m?js)")||prompt==null||prompt.isBlank()||prompt.length()>8192||diagnostics==null||diagnostics.length()>16000||hostApi==null||hostApi.length()>16000)
                throw new IllegalArgumentException("STUDIO_CODER_INPUT");
            dependencyDeclaration=dependencyDeclaration||javaDependencies!=null||scriptDependencies!=null;
            if(scriptDependencies!=null&&(javaDependencies!=null||!scriptDependencies.graph().root().equals(packageId)||path.toLowerCase(Locale.ROOT).endsWith(".java")||!shareScriptDependencySources))throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_SOURCE_CONSENT_REQUIRED");
            shareScriptDependencySources=scriptDependencies!=null&&shareScriptDependencySources;
            if(nativeSelection!=null&&(!nativeSelection.environment().equals(environment.fingerprint())||!shareNativeContext))throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_CONSENT_REQUIRED");
            shareNativeContext=nativeSelection!=null&&shareNativeContext;
            if(javaDependencies!=null&&(javaDependencies.nodes().isEmpty()||!javaDependencies.root().equals(packageId)||!path.toLowerCase(Locale.ROOT).endsWith(".java")))throw new IllegalArgumentException("STUDIO_CODER_DEPENDENCY_INPUT");
            additionalSources=additionalSources==null?Map.of():Map.copyOf(additionalSources);
            if(!workspace&&!additionalSources.isEmpty()||additionalSources.containsKey(path))throw new IllegalArgumentException("STUDIO_CODER_WORKSPACE_INPUT");
            if(workspace)CodeDraftSources.validate(path,refs(path,sourceHash,sourceBytes,additionalSources));
        }
        public Map<UUID,String> dependencies(){return javaDependencies!=null?javaDependencies.required():scriptDependencies!=null?scriptDependencies.graph().required():Map.of();}
        public String dependencyHash(){return javaDependencies!=null?javaDependencies.fingerprint():scriptDependencies!=null?scriptDependencies.graph().fingerprint():"";}
        public boolean hasDependencyContext(){return javaDependencies!=null||scriptDependencies!=null;}
        public boolean javaSource(){return path.toLowerCase(Locale.ROOT).endsWith(".java");}
        public Map<String,CodeDraft.SourceRef> files(){return refs(path,sourceHash,sourceBytes,additionalSources);}
        public int totalSourceBytes(){return sourceBytes+additionalSources.values().stream().mapToInt(CodeDraft.SourceRef::bytes).sum();}
    }
    public record Attempt(UUID id,int ordinal,String state,String rawHash,int rawBytes,String sourceHash,int sourceBytes,
            String provider,String requestedModel,String responseModel,String diagnostics,String artifact,String nativeClasspath,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String entry,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) Map<String,CodeDraft.SourceRef> additionalSources,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JavaDependencyApi.Snapshot dependencyApi,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ScriptDependencySources.Snapshot scriptDependencySources,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) NativeCoderContext.Snapshot nativeContext) {
        public Attempt(UUID id,int ordinal,String state,String rawHash,int rawBytes,String sourceHash,int sourceBytes,String provider,String requestedModel,String responseModel,String diagnostics,String artifact,String nativeClasspath,String entry,Map<String,CodeDraft.SourceRef> additionalSources,JavaDependencyApi.Snapshot dependencyApi,ScriptDependencySources.Snapshot scriptDependencySources){this(id,ordinal,state,rawHash,rawBytes,sourceHash,sourceBytes,provider,requestedModel,responseModel,diagnostics,artifact,nativeClasspath,entry,additionalSources,dependencyApi,scriptDependencySources,null);}
        public Attempt(UUID id,int ordinal,String state,String rawHash,int rawBytes,String sourceHash,int sourceBytes,String provider,String requestedModel,String responseModel,String diagnostics,String artifact,String nativeClasspath,String entry,Map<String,CodeDraft.SourceRef> additionalSources,JavaDependencyApi.Snapshot dependencyApi){this(id,ordinal,state,rawHash,rawBytes,sourceHash,sourceBytes,provider,requestedModel,responseModel,diagnostics,artifact,nativeClasspath,entry,additionalSources,dependencyApi,null);}
        public Attempt(UUID id,int ordinal,String state,String rawHash,int rawBytes,String sourceHash,int sourceBytes,String provider,String requestedModel,String responseModel,String diagnostics,String artifact,String nativeClasspath,String entry,Map<String,CodeDraft.SourceRef> additionalSources){this(id,ordinal,state,rawHash,rawBytes,sourceHash,sourceBytes,provider,requestedModel,responseModel,diagnostics,artifact,nativeClasspath,entry,additionalSources,null);}
        public Attempt(UUID id,int ordinal,String state,String rawHash,int rawBytes,String sourceHash,int sourceBytes,String provider,String requestedModel,String responseModel,String diagnostics,String artifact,String nativeClasspath){this(id,ordinal,state,rawHash,rawBytes,sourceHash,sourceBytes,provider,requestedModel,responseModel,diagnostics,artifact,nativeClasspath,"",Map.of());}
        public Attempt {
            if(dependencyApi!=null&&scriptDependencySources!=null)throw new IllegalArgumentException("STUDIO_CODER_DEPENDENCY_CONTEXT_TYPE");
            Objects.requireNonNull(id);
            if(ordinal<1||ordinal>3||!Set.of("REQUESTING","VALIDATING","ACCEPTED","REJECTED","FAILED","UNKNOWN").contains(state)||!hash(rawHash,true)||rawBytes<0||rawBytes>MAX_RAW_BYTES
                    ||!hash(sourceHash,true)||sourceBytes<0||sourceBytes>64000||!hash(artifact,true)||!hash(nativeClasspath,true)
                    ||provider==null||provider.length()>256||requestedModel==null||requestedModel.length()>256||responseModel==null||responseModel.length()>256||diagnostics==null||diagnostics.length()>16000)
                throw new IllegalArgumentException("STUDIO_CODER_ATTEMPT");
            entry=entry==null?"":entry;additionalSources=additionalSources==null?Map.of():Map.copyOf(additionalSources);
            if(sourceHash.isEmpty()&&!additionalSources.isEmpty()||!additionalSources.isEmpty()&&entry.isEmpty())throw new IllegalArgumentException("STUDIO_CODER_WORKSPACE_RESULT");
            if(!entry.isEmpty()&&!sourceHash.isEmpty())CodeDraftSources.validate(entry,refs(entry,sourceHash,sourceBytes,additionalSources));
        }
        public Map<String,CodeDraft.SourceRef> files(String fallback){return sourceHash.isEmpty()?Map.of():refs(entry.isEmpty()?fallback:entry,sourceHash,sourceBytes,additionalSources);}
        public String candidateHash(String fallback)throws Exception{return sourceHash.isEmpty()?"":CodeDraftSources.fingerprint(entry.isEmpty()?fallback:entry,files(fallback));}
        public String candidateHash(Input in)throws Exception{return sourceHash.isEmpty()?"":CodeDraftSources.fingerprint(entry.isEmpty()?in.path():entry,files(in.path()),in.dependencies());}
        public int nativeContextBytes(){return nativeContext==null?0:nativeContext.bytes();}
        public int dependencyApiBytes(){return dependencyApi!=null?dependencyApi.bytes():scriptDependencySources!=null?scriptDependencySources.bytes():0;}
        public int totalSourceBytes(){return sourceBytes+additionalSources.values().stream().mapToInt(CodeDraft.SourceRef::bytes).sum();}
    }
    public record Job(UUID id,Input input,String state,String error,List<Attempt> attempts,UUID adoptedDraft,long revision,long updatedAt) {
        public Job {
            Objects.requireNonNull(id);Objects.requireNonNull(input);attempts=List.copyOf(attempts);
            if(!id.equals(input.operation())||!STATES.contains(state)||error==null||!error.matches("[A-Z0-9_]{0,100}")||attempts.size()>input.maxAttempts()||revision<1)
                throw new IllegalArgumentException("STUDIO_CODER_RECORD");
            for(int i=0;i<attempts.size();i++){
                var attempt=attempts.get(i);
                if(input.nativeSelection()!=null&&Set.of("VALIDATING","ACCEPTED").contains(attempt.state())&&attempt.nativeContext()==null)throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_RECORD");
                if(attempt.nativeContext()!=null&&(input.nativeSelection()==null||!attempt.nativeContext().selectionHash().equals(input.nativeSelection().fingerprint())))throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_RECORD");
                if(input.scriptDependencies()!=null&&Set.of("VALIDATING","ACCEPTED").contains(attempt.state())&&attempt.scriptDependencySources()==null)throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_RECORD");
                if(attempt.scriptDependencySources()!=null&&(input.scriptDependencies()==null||!attempt.scriptDependencySources().graphHash().equals(input.dependencyHash())))throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_RECORD");
                if(input.javaDependencies()!=null&&Set.of("VALIDATING","ACCEPTED").contains(attempt.state())&&attempt.dependencyApi()==null)throw new IllegalArgumentException("STUDIO_CODER_DEPENDENCY_RECORD");if(attempt.dependencyApi()!=null&&(input.javaDependencies()==null||!attempt.dependencyApi().graphHash().equals(input.dependencyHash())))throw new IllegalArgumentException("STUDIO_CODER_DEPENDENCY_RECORD");if(attempt.ordinal()!=i+1)throw new IllegalArgumentException("STUDIO_CODER_RECORD");
                if(!attempt.sourceHash().isEmpty()&&(input.workspace()?!attempt.entry().equals(input.path()):!attempt.entry().isEmpty()||!attempt.additionalSources().isEmpty()))throw new IllegalArgumentException("STUDIO_CODER_WORKSPACE_RECORD");
            }
        }
        public Attempt last(){return attempts.isEmpty()?null:attempts.getLast();}
    }
    private final SqliteRuntimeRepository repo;
    private final UUID world;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<UUID,Job> jobs=new LinkedHashMap<>();
    public StudioCoderJournal(SqliteRuntimeRepository repo,UUID world,Clock clock)throws Exception {
        this.repo=repo;this.world=world;this.clock=clock;
        for(var row:repo.list(world,NS)){var job=json.readValue(row.payload(),Job.class);verify(job,row.revision(),row.recordId());jobs.put(job.id(),job);}
        if(jobs.size()>4096)throw new IllegalStateException("STUDIO_CODER_RECORD_LIMIT");
        for(var job:List.copyOf(jobs.values()))if(Set.of("PENDING","GENERATING","VALIDATING").contains(job.state())){
            var attempts=new ArrayList<>(job.attempts());
            if(job.last()!=null&&Set.of("REQUESTING","VALIDATING").contains(job.last().state()))attempts.set(attempts.size()-1,copyAttempt(job.last(),"UNKNOWN",job.last().diagnostics(),job.last().artifact(),job.last().nativeClasspath()));
            save(job,"INTERRUPTED","STUDIO_CODER_RESTART_NOT_REPLAYED",attempts,null);
        }
    }
    private static boolean hash(String value,boolean empty){return value!=null&&value.matches(empty?"(?:[a-f0-9]{64})?":"[a-f0-9]{64}");}
    private static Map<String,CodeDraft.SourceRef> refs(String entry,String hash,int bytes,Map<String,CodeDraft.SourceRef> additional){var files=new TreeMap<>(additional);files.put(entry,new CodeDraft.SourceRef(hash,bytes));return Collections.unmodifiableMap(files);}
    private void verify(Job job,long revision,String id){if(!job.input().world().equals(world)||job.revision()!=revision||!job.id().toString().equals(id))throw new IllegalStateException("STUDIO_CODER_RECORD");}
    public synchronized Optional<Job> find(UUID id){return Optional.ofNullable(jobs.get(id));}
    public synchronized Job get(UUID id){return find(id).orElseThrow(()->new IllegalStateException("STUDIO_CODER_JOB_MISSING"));}
    public synchronized Optional<Job> refresh(UUID id)throws Exception {
        var row=repo.get(world,NS,id.toString()).orElse(null);if(row==null){if(jobs.containsKey(id))throw new IllegalStateException("STUDIO_CODER_JOB_MISSING");return Optional.empty();}
        var job=json.readValue(row.payload(),Job.class);verify(job,row.revision(),row.recordId());jobs.put(id,job);return Optional.of(job);
    }
    public synchronized List<Job> list(UUID owner){return jobs.values().stream().filter(j->j.input().owner().equals(owner)).sorted(Comparator.comparingLong(Job::updatedAt).reversed().thenComparing(Job::id)).toList();}
    public synchronized List<Job> interrupted(){return jobs.values().stream().filter(j->j.state().equals("INTERRUPTED")).toList();}
    public static UUID adoptionOperation(Input in){return UUID.nameUUIDFromBytes(("studio-coder-adopt|"+in.world()+"|"+in.operation()).getBytes(StandardCharsets.UTF_8));}
    public static UUID targetDraft(Input in){return CodeDraftService.idempotentDraftId(in.world(),in.owner(),adoptionOperation(in));}
    public synchronized Optional<Job> sourceJob(dev.mineagent.runtime.api.packages.CodeDraft draft){return jobs.values().stream().filter(j->Set.of("ADOPTING","ADOPTED").contains(j.state())&&targetDraft(j.input()).equals(draft.draftId())&&j.input().owner().equals(draft.ownerPlayerId())&&j.input().packageId().equals(draft.packageId())&&j.input().task().equals(draft.taskId())).findFirst();}
    public synchronized Optional<Job> nativePublicationSource(dev.mineagent.runtime.api.packages.CodeDraft draft){return sourceJob(draft).filter(j->{try{return j.state().equals("ADOPTED")&&j.input().nativeSelection()!=null&&j.last()!=null&&j.last().state().equals("ACCEPTED")&&j.last().nativeContext()!=null&&j.last().candidateHash(j.input()).equals(CodeDraftSources.fingerprint(draft));}catch(Exception invalid){throw new IllegalStateException("STUDIO_CODER_NATIVE_PUBLICATION_SOURCE",invalid);}});}
    public synchronized void capacity(int sourceBytes)throws Exception {
        if(jobs.size()>=4096||metadataUsage()+RECORD_RESERVE>META_BUDGET||rawUsage()+sourceBytes+MAX_RAW_BYTES+CodeDraftSources.MAX_BYTES+JavaDependencyApi.MAX_BYTES+NativeCoderContext.MAX_BYTES>RAW_BUDGET)
            throw new IllegalStateException("STUDIO_CODER_STORAGE_BUDGET");
    }
    public synchronized Job begin(Input input)throws Exception {
        if(!input.world().equals(world))throw new IllegalArgumentException("STUDIO_CODER_WORLD");
        var existing=refresh(input.operation()).orElse(null);
        if(existing!=null){if(!existing.input().equals(input))throw new IllegalStateException("STUDIO_CODER_OPERATION_REUSED");return existing;}
        capacity(input.totalSourceBytes());
        var job=new Job(input.operation(),input,"PENDING","",List.of(),null,1,clock.millis());
        if(json.writeValueAsBytes(job).length+(long)input.maxAttempts()*ATTEMPT_RESERVE+4096>RECORD_RESERVE)throw new IllegalStateException("STUDIO_CODER_CONTEXT_BUDGET");
        write(null,job);return job;
    }
    /** An uncertain return is NOT a dispatch permit. Reobserved REQUESTING is never sent again. */
    public synchronized Job beginAttempt(UUID id)throws Exception {
        var job=refresh(id).orElseThrow();
        if(!job.state().equals("PENDING")||job.attempts().size()>=job.input().maxAttempts()
                ||job.last()!=null&&!job.last().state().equals("REJECTED"))throw new IllegalStateException("STUDIO_CODER_ATTEMPT_STATE");
        if(rawUsage()+MAX_RAW_BYTES+CodeDraftSources.MAX_BYTES+JavaDependencyApi.MAX_BYTES+NativeCoderContext.MAX_BYTES>RAW_BUDGET)throw new IllegalStateException("STUDIO_CODER_STORAGE_BUDGET");
        int ordinal=job.attempts().size()+1;
        UUID attempt=UUID.nameUUIDFromBytes(("studio-coder|"+world+"|"+id+"|"+ordinal).getBytes(StandardCharsets.UTF_8));
        var attempts=new ArrayList<>(job.attempts());attempts.add(new Attempt(attempt,ordinal,"REQUESTING","",0,"",0,"","","","","",""));
        return save(job,"GENERATING","",attempts,null);
    }
    public synchronized Job bindDependencyApi(UUID id,UUID attempt,JavaDependencyApi.Snapshot api)throws Exception {
        var job=refresh(id).orElseThrow();var old=job.last();
        if(old==null||!old.id().equals(attempt)||!job.state().equals("GENERATING")||!old.state().equals("REQUESTING")||!api.graphHash().equals(job.input().dependencyHash()))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_CONTEXT_CHANGED");
        if(old.dependencyApi()!=null){if(!old.dependencyApi().equals(api))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CHANGED");return job;}
        var attempts=new ArrayList<>(job.attempts());attempts.set(attempts.size()-1,withDependencyApi(old,api));return save(job,job.state(),job.error(),attempts,job.adoptedDraft());
    }
    public static Attempt withDependencyApi(Attempt a,JavaDependencyApi.Snapshot api){return new Attempt(a.id(),a.ordinal(),a.state(),a.rawHash(),a.rawBytes(),a.sourceHash(),a.sourceBytes(),a.provider(),a.requestedModel(),a.responseModel(),a.diagnostics(),a.artifact(),a.nativeClasspath(),a.entry(),a.additionalSources(),api,a.scriptDependencySources(),a.nativeContext());}
    public synchronized Job bindScriptDependencySources(UUID id,UUID attempt,ScriptDependencySources.Snapshot sources)throws Exception {
        var job=refresh(id).orElseThrow();var old=job.last();
        if(old==null||!old.id().equals(attempt)||!job.state().equals("GENERATING")||!old.state().equals("REQUESTING")||job.input().scriptDependencies()==null||!sources.graphHash().equals(job.input().dependencyHash()))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");
        if(old.scriptDependencySources()!=null){if(!old.scriptDependencySources().equals(sources))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");return job;}
        var attempts=new ArrayList<>(job.attempts());attempts.set(attempts.size()-1,withScriptDependencySources(old,sources));return save(job,job.state(),job.error(),attempts,job.adoptedDraft());
    }
    public static Attempt withScriptDependencySources(Attempt a,ScriptDependencySources.Snapshot sources){return new Attempt(a.id(),a.ordinal(),a.state(),a.rawHash(),a.rawBytes(),a.sourceHash(),a.sourceBytes(),a.provider(),a.requestedModel(),a.responseModel(),a.diagnostics(),a.artifact(),a.nativeClasspath(),a.entry(),a.additionalSources(),a.dependencyApi(),sources,a.nativeContext());}
    public synchronized Job bindNativeContext(UUID id,UUID attempt,NativeCoderContext.Snapshot context)throws Exception {
        var job=refresh(id).orElseThrow();var old=job.last();
        if(old==null||!old.id().equals(attempt)||!job.state().equals("GENERATING")||!old.state().equals("REQUESTING")||job.input().nativeSelection()==null||!context.selectionHash().equals(job.input().nativeSelection().fingerprint()))throw new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_CHANGED");
        if(old.nativeContext()!=null){if(!old.nativeContext().equals(context))throw new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_CHANGED");return job;}
        var attempts=new ArrayList<>(job.attempts());attempts.set(attempts.size()-1,withNativeContext(old,context));return save(job,job.state(),job.error(),attempts,job.adoptedDraft());
    }
    public static Attempt withNativeContext(Attempt a,NativeCoderContext.Snapshot context){return new Attempt(a.id(),a.ordinal(),a.state(),a.rawHash(),a.rawBytes(),a.sourceHash(),a.sourceBytes(),a.provider(),a.requestedModel(),a.responseModel(),a.diagnostics(),a.artifact(),a.nativeClasspath(),a.entry(),a.additionalSources(),a.dependencyApi(),a.scriptDependencySources(),context);}
    /** Raw/source evidence can be attached after cancellation; this cannot make the job active again. */
    public synchronized Job result(UUID id,UUID attempt,Attempt result,String nextState,String error)throws Exception {
        var job=refresh(id).orElseThrow();var old=job.last();
        if(old==null||!old.id().equals(attempt)||!result.id().equals(attempt)||old.ordinal()!=result.ordinal())throw new IllegalStateException("STUDIO_CODER_ATTEMPT_CHANGED");
        if(!Set.of("REQUESTING","VALIDATING").contains(old.state()))return job;
        if(old.nativeContext()!=null&&!old.nativeContext().equals(result.nativeContext()))throw new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_CHANGED");
        if(old.scriptDependencySources()!=null&&!old.scriptDependencySources().equals(result.scriptDependencySources()))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");
        if(old.dependencyApi()!=null&&!old.dependencyApi().equals(result.dependencyApi()))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CHANGED");
        var attempts=new ArrayList<>(job.attempts());attempts.set(attempts.size()-1,result);
        return save(job,Set.of("GENERATING","VALIDATING").contains(job.state())?nextState:job.state(),
                Set.of("GENERATING","VALIDATING").contains(job.state())?error:job.error(),attempts,job.adoptedDraft());
    }
    public synchronized Job state(UUID id,long expected,String state,String code)throws Exception {
        var job=refresh(id).orElseThrow();if(job.revision()!=expected)throw new IllegalStateException("STUDIO_CODER_JOB_CHANGED");
        boolean allowed=switch(state){
            case "PENDING" -> job.state().equals("GENERATING")&&job.last()!=null&&job.last().state().equals("REJECTED")&&job.attempts().size()<job.input().maxAttempts();
            case "ADOPTING" -> job.state().equals("READY");
            case "CANCELLED","STALE","FAILED","INTERRUPTED" -> ACTIVE.contains(job.state())&&!job.state().equals("ADOPTING");
            default -> false;
        };
        if(!allowed)throw new IllegalStateException("STUDIO_CODER_TRANSITION");
        return save(job,state,code,job.attempts(),job.adoptedDraft());
    }
    public synchronized Job adopted(UUID id,UUID draft)throws Exception {
        var job=refresh(id).orElseThrow();
        if(job.state().equals("ADOPTED")){if(!draft.equals(job.adoptedDraft()))throw new IllegalStateException("STUDIO_CODER_ADOPTION_CHANGED");return job;}
        if(!job.state().equals("ADOPTING")||job.last()==null||!job.last().state().equals("ACCEPTED"))throw new IllegalStateException("STUDIO_CODER_ADOPTION_STATE");
        return save(job,"ADOPTED","",job.attempts(),draft);
    }
    private Job save(Job old,String state,String error,List<Attempt> attempts,UUID draft)throws Exception {
        var next=new Job(old.id(),old.input(),state,error,attempts,draft,old.revision()+1,clock.millis());write(old,next);return next;
    }
    private void write(Job old,Job next)throws Exception {
        String payload=json.writeValueAsString(next);if(payload.getBytes(StandardCharsets.UTF_8).length>RECORD_RESERVE)throw new IllegalStateException("STUDIO_CODER_METADATA_LIMIT");
        var result=repo.compareAndSet(world,NS,next.id().toString(),old==null?0:old.revision(),payload,clock.millis());
        if(!result.accepted()){refresh(next.id());throw new IllegalStateException("STUDIO_CODER_CAS");}
        jobs.put(next.id(),next);
    }
    private long metadataUsage()throws Exception {long value=0;for(var job:jobs.values())value+=ACTIVE.contains(job.state())?RECORD_RESERVE:json.writeValueAsBytes(job).length;return value;}
    private long rawUsage(){long value=0;for(var job:jobs.values()){value+=job.input().totalSourceBytes();for(var a:job.attempts())value+=Set.of("REQUESTING","UNKNOWN").contains(a.state())&&a.rawHash().isEmpty()?MAX_RAW_BYTES+(long)CodeDraftSources.MAX_BYTES+JavaDependencyApi.MAX_BYTES+NativeCoderContext.MAX_BYTES:(long)a.rawBytes()+a.totalSourceBytes()+a.dependencyApiBytes()+a.nativeContextBytes();}return value;}
    public static Attempt copyAttempt(Attempt a,String state,String diagnostics,String artifact,String classpath){
        return new Attempt(a.id(),a.ordinal(),state,a.rawHash(),a.rawBytes(),a.sourceHash(),a.sourceBytes(),a.provider(),a.requestedModel(),a.responseModel(),diagnostics,artifact,classpath,a.entry(),a.additionalSources(),a.dependencyApi(),a.scriptDependencySources(),a.nativeContext());
    }
}
