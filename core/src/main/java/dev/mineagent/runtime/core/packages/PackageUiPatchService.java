package dev.mineagent.runtime.core.packages;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.task.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Scope-bound candidate/commit journal; the historical UI API and namespace remain compatible. No Provider replay. */
public final class PackageUiPatchService implements AutoCloseable {
    private final String NS,generateStep,applyStep;private final boolean worldMode;
    private final SqliteRuntimeRepository repo;private final UUID world;private final Clock clock;private final TaskManager tasks;private final RuntimePackageLibrary library;
    private final ObjectMapper json=new ObjectMapper();private final dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs<PackageUiPatchJob> jobs;private boolean closed;
    public record Submission(PackageUiPatchJob job,boolean duplicate){}
    public record RebuildEvidence(PackageUiPatchJob failedJob,String rawHash,String attribution,String candidateHash){}
    private static final String REBUILD_NS="package_ui_patch_rebuilds_v1";
    private RebuildEvidence readRebuild(UUID operation){
        try{var row=repo.getIncludingDeleted(world,REBUILD_NS,operation.toString());if(row.isEmpty())return null;if(row.get().deleted())throw new IllegalStateException("PACKAGE_JOB_REMOVED");
            var e=json.readValue(row.get().payload(),RebuildEvidence.class);var original=e.failedJob();var current=jobs.get(operation);
            if(row.get().revision()!=1||!original.operationId().equals(operation)||!original.state().equals("FAILED")||!original.worldId().equals(world)||current==null||!current.taskId().equals(original.taskId())||!current.ownerPlayerId().equals(original.ownerPlayerId())||!current.base().canonicalSha256().equals(original.base().canonicalSha256()))throw new IllegalStateException("UI_PATCH_REBUILD_RECORD");return e;
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("PACKAGE_JOB_READ_UNAVAILABLE",failure);}
    }
    public synchronized PackageUiPatchJob rebuildFailed(UUID owner,UUID operation,RuntimePackage candidate,String rawHash,boolean operatorConfirmed)throws Exception{
        requireOpen();var job=owned(owner,operation);if(worldMode||!operatorConfirmed)throw new SecurityException("UI_PATCH_REBUILD_OPERATOR_REQUIRED");
        if(rawHash==null||!rawHash.matches("[a-f0-9]{64}")||candidate==null)throw new IllegalArgumentException("UI_PATCH_REBUILD_INPUT");
        var prior=readRebuild(operation);
        if(prior!=null){if(!prior.rawHash().equals(rawHash)||!prior.candidateHash().equals(candidate.canonicalSha256()))throw new IllegalArgumentException("UI_PATCH_REBUILD_CHANGED");if(Set.of("READY","APPLIED","ROLLED_BACK").contains(job.state()))return job;}
        if(!job.state().equals("FAILED")||!currentTask(job)||!matches(library.get(job.base().packageId()).orElse(null),job.base(),job.base().revision()))throw new IllegalStateException("UI_PATCH_REBUILD_STALE");
        if(!job.rawOutputSha256().isEmpty()&&!job.rawOutputSha256().equals(rawHash))throw new IllegalArgumentException("UI_PATCH_REBUILD_CHANGED");
        policy(job.base(),candidate);String invalid=library.validateCandidate(candidate);if(!invalid.isEmpty())throw new IllegalArgumentException("UI_PATCH_REBUILD_INVALID");
        if(prior==null){var evidence=new RebuildEvidence(job,rawHash,job.rawOutputSha256().isEmpty()?"OPERATOR_ATTESTED_RAW":"STORED_PROVIDER_RAW",candidate.canonicalSha256());if(!repo.compareAndSet(world,REBUILD_NS,operation.toString(),0,json.writeValueAsString(evidence),clock.millis()).accepted())throw new IllegalStateException("UI_PATCH_REBUILD_CAS");}
        // Original FAILED snapshot is immutable in REBUILD_NS. No Provider dispatch and no automatic application.
        return save(job,"READY","",candidate,job.providerId().isEmpty()?"operator-attested-output":job.providerId(),rawHash,0);
    }
    public synchronized Optional<RebuildEvidence> rebuildEvidence(UUID owner,UUID operation){requireOpen();owned(owner,operation);return Optional.ofNullable(readRebuild(operation));}
    private PackageUiPatchService(SqliteRuntimeRepository repo,UUID world,Clock clock,TaskManager tasks,RuntimePackageLibrary library,boolean worldMode)throws Exception{
        this.repo=repo;this.world=world;this.clock=clock;this.tasks=tasks;this.library=library;
        this.worldMode=worldMode;NS=worldMode?"package_world_patch_jobs_v1":"package_ui_patch_jobs";generateStep=worldMode?"generate_world_patch":"generate_ui_patch";applyStep=worldMode?"apply_world_patch":"apply_ui_patch";
        repo.initializePackageJobRetention(world);
        jobs=new dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs<>(repo,world,NS,PackageUiPatchJob.class,j->Set.of("PENDING","READY","APPLYING","ROLLING_BACK").contains(j.state()),PackageUiPatchJob::operationId,PackageUiPatchJob::worldId,PackageUiPatchJob::revision);
        jobs.loadActive();
        for(var job:List.copyOf(jobs.values())){
            if(job.state().equals("PENDING")){pause(job);save(job,"INTERRUPTED","SERVER_RESTARTED",null,"","",0);}
            else if(job.state().equals("APPLYING")||job.state().equals("ROLLING_BACK")){
                boolean undo=job.state().equals("ROLLING_BACK");var target=undo?job.base():job.candidate();long revision=undo?job.headRevision()+1:job.base().revision()+1;
                var head=library.get(job.base().packageId()).orElse(null);
                if(matches(head,target,revision)){var recovered=save(job,undo?"ROLLED_BACK":"APPLIED","",job.candidate(),job.providerId(),job.rawOutputSha256(),revision);if(!undo)finishTask(recovered,false);}
                else if(!undo&&matches(head,job.base(),job.base().revision()))save(job,"READY","RECOVERED_BEFORE_COMMIT",job.candidate(),job.providerId(),job.rawOutputSha256(),0);
                else if(undo&&matches(head,job.candidate(),job.headRevision()))save(job,"APPLIED","RECOVERED_BEFORE_ROLLBACK",job.candidate(),job.providerId(),job.rawOutputSha256(),job.headRevision());
                else {pause(job);save(job,"STALE","RECOVERY_CONFLICT",job.candidate(),job.providerId(),job.rawOutputSha256(),job.headRevision());}
            }
        }
    }
    public static PackageUiPatchService open(Path database,UUID world,Clock clock,TaskManager tasks,RuntimePackageLibrary library)throws Exception{
        var repo=new SqliteRuntimeRepository(database);try{return new PackageUiPatchService(repo,world,clock,tasks,library,false);}catch(Exception e){repo.close();throw e;}
    }
    public static PackageUiPatchService openWorld(Path database,UUID world,Clock clock,TaskManager tasks,RuntimePackageLibrary library)throws Exception{
        var repo=new SqliteRuntimeRepository(database);try{return new PackageUiPatchService(repo,world,clock,tasks,library,true);}catch(Exception e){repo.close();throw e;}
    }
    public synchronized Submission submit(UUID owner,UUID agent,UUID operation,RuntimePackage suppliedBase,String prompt,boolean authorized)throws Exception{
        return submit(owner,agent,operation,suppliedBase,prompt,authorized,null);
    }
    public synchronized Submission submit(UUID owner,UUID agent,UUID operation,RuntimePackage suppliedBase,String prompt,boolean authorized,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent)throws Exception{
        requireOpen();if(!authorized)throw new SecurityException("PERMISSION_DENIED");Objects.requireNonNull(owner);Objects.requireNonNull(agent);Objects.requireNonNull(operation);
        if(prompt==null||prompt.isBlank()||prompt.length()>8192)throw new IllegalArgumentException("UI_PATCH_PROMPT");prompt=prompt.strip();
        var old=jobs.get(operation);if(old!=null){requireOwner(old,owner);if(!old.agentId().equals(agent)||!old.prompt().equals(prompt)||!matches(old.base(),suppliedBase,suppliedBase.revision()))throw new IllegalArgumentException("OPERATION_ID_REUSED");return new Submission(old,true);}
        var base=library.get(suppliedBase.packageId()).orElseThrow(()->new IllegalStateException("PACKAGE_MISSING"));if(!matches(base,suppliedBase,suppliedBase.revision()))throw new IllegalStateException("STALE_PACKAGE");
        if(worldMode)WorldPatchPolicy.requireBase(base);
        var active=jobs.values().stream().filter(j->Set.of("PENDING","READY","APPLYING","ROLLING_BACK").contains(j.state())).toList();
        if(active.size()>=4||active.stream().anyMatch(j->j.ownerPlayerId().equals(owner)||j.base().packageId().equals(base.packageId())))throw new IllegalStateException("UI_PATCH_BUSY");
        var task=tasks.create(agent,owner,(worldMode?"世界内容修改: ":"网页修改: ")+prompt.substring(0,Math.min(80,prompt.length())),60,List.of(new TaskStepSpec(generateStep,Set.of()),new TaskStepSpec(applyStep,Set.of(generateStep))),parent);
        var job=new PackageUiPatchJob(operation,world,owner,agent,task.taskId(),task.intentRevision(),base,null,prompt,"PENDING","","","",0,1,clock.millis());
        try{if(!repo.compareAndSet(world,NS,operation.toString(),0,json.writeValueAsString(job),clock.millis()).accepted())throw new IllegalStateException("UI_PATCH_CAS");}
        catch(Exception e){tasks.transition(task.taskId(),task.revision(),true,TaskStatus.CANCELLED);throw e;}
        jobs.put(operation,job);return new Submission(job,false);
    }
    public synchronized PackageUiPatchJob ready(PackageUiPatchJob ticket,RuntimePackage candidate,String provider,String raw,boolean authorized)throws Exception{
        requireOpen();var job=ticket(ticket);if(!job.state().equals("PENDING"))return job;
        if(!authorized)return fail(ticket,"PERMISSION_DENIED");if(!currentTask(job))return fail(ticket,"STALE_TASK");
        try{
            policy(job.base(),candidate);if(provider==null||!provider.matches("[A-Za-z0-9_.-]{1,128}")||raw==null||!raw.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("UI_PATCH_PROVENANCE");
            String error=library.validateCandidate(candidate);if(!error.isEmpty())return fail(ticket,error);
        }catch(IllegalArgumentException invalid){return fail(ticket,"UI_PATCH_SCOPE");}
        if(!matches(library.get(job.base().packageId()).orElse(null),job.base(),job.base().revision()))return fail(ticket,"STALE_PACKAGE");
        var ready=save(job,"READY","",candidate,provider,raw,0);if(tasks.get(job.taskId()).orElseThrow().status()==TaskStatus.RUNNING)step(ready,generateStep);return ready;
    }
    public synchronized PackageUiPatchJob apply(UUID owner,UUID operation,boolean authorized)throws Exception{
        requireOpen();var job=owned(owner,operation);if(!authorized)throw new SecurityException("PERMISSION_DENIED");
        if(job.state().equals("APPLIED")){finishTask(job,true);return job;}
        if(!job.state().equals("READY"))throw new IllegalStateException("UI_PATCH_NOT_READY");
        if(!currentTask(job)||!matches(library.get(job.base().packageId()).orElse(null),job.base(),job.base().revision())){pause(job);return save(job,"STALE","STALE_PACKAGE_OR_TASK",job.candidate(),job.providerId(),job.rawOutputSha256(),0);}
        resume(job);step(job,generateStep);policy(job.base(),job.candidate());
        job=save(job,"APPLYING","",job.candidate(),job.providerId(),job.rawOutputSha256(),0);
        RuntimePackageMutationResult changed;
        try{changed=library.upgrade(job.candidate(),job.base().revision());}
        catch(IllegalStateException capacity){if(libraryBudget(capacity)){save(job,"READY",capacity.getMessage(),job.candidate(),job.providerId(),job.rawOutputSha256(),0);}throw capacity;}
        if(!changed.accepted()){pause(job);return save(job,"STALE",changed.errorCode(),job.candidate(),job.providerId(),job.rawOutputSha256(),0);}
        var applied=save(job,"APPLIED","",job.candidate(),job.providerId(),job.rawOutputSha256(),job.base().revision()+1);finishTask(applied,true);return applied;
    }
    public synchronized PackageUiPatchJob rollback(UUID owner,UUID operation,boolean authorized)throws Exception{
        requireOpen();var job=owned(owner,operation);if(!authorized)throw new SecurityException("PERMISSION_DENIED");if(job.state().equals("ROLLED_BACK"))return job;
        var current=library.get(job.base().packageId()).orElse(null);long start=current!=null?current.revision():job.headRevision();
        if(!job.state().equals("APPLIED")||start<job.headRevision()||!matches(current,job.candidate(),start))throw new IllegalStateException("UI_PATCH_ROLLBACK_CONFLICT");
        job=save(job,"ROLLING_BACK","",job.candidate(),job.providerId(),job.rawOutputSha256(),start);RuntimePackageMutationResult result;
        try{result=library.upgrade(job.base(),job.headRevision());}
        catch(IllegalStateException capacity){if(libraryBudget(capacity)){save(job,"APPLIED",capacity.getMessage(),job.candidate(),job.providerId(),job.rawOutputSha256(),start);}throw capacity;}
        if(!result.accepted())return save(job,"STALE",result.errorCode(),job.candidate(),job.providerId(),job.rawOutputSha256(),job.headRevision());
        return save(job,"ROLLED_BACK","",job.candidate(),job.providerId(),job.rawOutputSha256(),job.headRevision()+1);
    }
    public synchronized PackageUiPatchJob fail(PackageUiPatchJob ticket,String error)throws Exception{requireOpen();var job=ticket(ticket);if(!job.state().equals("PENDING"))return job;pause(job);return save(job,"FAILED",error!=null&&error.matches("[A-Z][A-Z0-9_]{0,63}")?error:"UI_PATCH_FAILED",null,"","",0);}
    public synchronized PackageUiPatchJob failed(PackageUiPatchJob ticket,String error,String provider,String raw)throws Exception{
        requireOpen();var job=ticket(ticket);if(!job.state().equals("PENDING"))return job;
        if(provider==null||!provider.matches("[A-Za-z0-9_.-]{1,128}")||raw==null||!raw.matches("[0-9a-f]{64}"))return fail(ticket,error);
        pause(job);return save(job,"FAILED",error!=null&&error.matches("[A-Z][A-Z0-9_]{0,63}")?error:"UI_PATCH_FAILED",null,provider,raw,0);
    }
    public synchronized PackageUiPatchJob get(UUID owner,UUID operation){requireOpen();return owned(owner,operation);}
    public synchronized Optional<PackageUiPatchJob> find(UUID owner,UUID operation){requireOpen();var job=jobs.get(operation);if(job!=null)requireOwner(job,owner);return Optional.ofNullable(job);}
    public synchronized Optional<RuntimePackage> candidate(UUID owner,UUID operation){
        requireOpen();var job=owned(owner,operation);
        return job.state().equals("READY")&&currentTask(job)&&matches(library.get(job.base().packageId()).orElse(null),job.base(),job.base().revision())?Optional.of(job.candidate()):Optional.empty();
    }
    public synchronized PackageUiPatchJob cancel(UUID owner,UUID operation)throws Exception{
        requireOpen();var job=owned(owner,operation);if(!Set.of("PENDING","READY").contains(job.state()))return job;
        if(currentTask(job)){var t=tasks.get(job.taskId()).orElseThrow();tasks.transition(t.taskId(),t.revision(),true,TaskStatus.CANCELLED);}
        return save(job,"CANCELLED","USER_CANCELLED",job.candidate(),job.providerId(),job.rawOutputSha256(),0);
    }
    public synchronized List<PackageUiPatchJob> list(UUID owner){requireOpen();return jobs.page(dev.mineagent.runtime.core.persistence.PackageJobRetention.Query.all(owner),0,64);}
    public synchronized boolean ownsPublished(UUID owner,UUID pkg,long revision){requireOpen();var head=library.get(pkg).orElse(null);if(head==null||head.revision()!=revision)return false;try{return repo.packageJobOwnedHead(world,NS,owner,pkg,revision,head.canonicalSha256());}catch(java.sql.SQLException failure){throw new IllegalStateException("PACKAGE_JOB_READ_UNAVAILABLE",failure);}}
    public synchronized dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs.Page<PackageUiPatchJob> history(UUID owner,String state,String archive,int offset){requireOpen();return jobs.history(owner,state,archive,offset,8);}
    /** Preserve late verified raw evidence without reviving a cancelled/failed job or installing a candidate. */
    public synchronized void recordOutputEvidence(PackageUiPatchJob ticket,String provider,String raw)throws Exception{
        requireOpen();if(provider==null||!provider.matches("[A-Za-z0-9_.-]{1,128}")||raw==null||!raw.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("UI_PATCH_OUTPUT_EVIDENCE");
        var job=ticket(ticket);if(job.state().equals("PENDING"))throw new IllegalStateException("UI_PATCH_NOT_TERMINAL");
        if(!job.rawOutputSha256().isEmpty()){if(!job.rawOutputSha256().equals(raw)||!job.providerId().equals(provider))throw new IllegalStateException("UI_PATCH_EVIDENCE_CHANGED");return;}
        save(job,job.state(),job.errorCode(),job.candidate(),provider,raw,job.headRevision());
    }
    /** Trusted retained manifests for frozen Native assets, not permission to execute an old revision. */
    public synchronized Optional<RuntimePackage> version(UUID pkg,String hash)throws Exception{
        if(pkg==null||hash==null||!hash.matches("[a-f0-9]{64}"))return Optional.empty();
        requireOpen();
        for(var j:jobs.page(new dev.mineagent.runtime.core.persistence.PackageJobRetention.Query(null,"ALL","ALL",pkg,hash,null,null),0,1)){
            RuntimePackage p=j.base().canonicalSha256().equals(hash)?j.base():j.headRevision()>0&&j.candidate()!=null&&j.candidate().canonicalSha256().equals(hash)?j.candidate():null;
            if(p!=null){if(!library.validateCandidate(p).isEmpty())throw new IllegalStateException("WORLD_PATCH_ARCHIVE_INVALID");return Optional.of(p);}
        }return Optional.empty();
    }
    private void policy(RuntimePackage base,RuntimePackage candidate){if(worldMode)WorldPatchPolicy.require(base,candidate);else UiPatchPolicy.require(base,candidate);}
    private PackageUiPatchJob owned(UUID owner,UUID operation){var j=jobs.get(operation);if(j==null)throw new SecurityException("UI_PATCH_NOT_OWNED");requireOwner(j,owner);return j;}
    private void requireOwner(PackageUiPatchJob j,UUID owner){if(!j.ownerPlayerId().equals(owner))throw new SecurityException("UI_PATCH_NOT_OWNED");}
    private PackageUiPatchJob ticket(PackageUiPatchJob t){var j=owned(t.ownerPlayerId(),t.operationId());if(!j.worldId().equals(t.worldId())||!j.agentId().equals(t.agentId())||!j.taskId().equals(t.taskId())||j.taskIntentRevision()!=t.taskIntentRevision()||!j.prompt().equals(t.prompt())||!matches(j.base(),t.base(),t.base().revision()))throw new IllegalArgumentException("UI_PATCH_TICKET");return j;}
    private boolean currentTask(PackageUiPatchJob j){var t=tasks.get(j.taskId()).orElse(null);return t!=null&&t.worldId().equals(world)&&t.ownerPlayerId().equals(j.ownerPlayerId())&&t.agentId().equals(j.agentId())&&t.intentRevision()==j.taskIntentRevision()&&Set.of(TaskStatus.RUNNING,TaskStatus.PAUSED).contains(t.status());}
    private void resume(PackageUiPatchJob j)throws Exception{var t=tasks.get(j.taskId()).orElseThrow();if(t.status()==TaskStatus.PAUSED&&!tasks.transition(t.taskId(),t.revision(),true,TaskStatus.RUNNING).accepted())throw new IllegalStateException("UI_PATCH_TASK_RESUME");}
    private void pause(PackageUiPatchJob j)throws Exception{if(currentTask(j)){var t=tasks.get(j.taskId()).orElseThrow();if(t.status()==TaskStatus.RUNNING)tasks.transition(t.taskId(),t.revision(),true,TaskStatus.PAUSED);}}
    private void step(PackageUiPatchJob j,String id)throws Exception{var t=tasks.get(j.taskId()).orElseThrow();if(t.steps().stream().anyMatch(s->s.stepId().equals(id)&&s.status()==TaskNodeStatus.COMPLETED))return;if(!tasks.completeStep(t.taskId(),t.revision(),id).accepted())throw new IllegalStateException("UI_PATCH_TASK_COMMIT");}
    private void finishTask(PackageUiPatchJob j,boolean explicit)throws Exception{if(!currentTask(j))return;var t=tasks.get(j.taskId()).orElseThrow();if(t.status()==TaskStatus.PAUSED&&!explicit)return;if(explicit)resume(j);step(j,generateStep);step(j,applyStep);}
    private PackageUiPatchJob save(PackageUiPatchJob j,String state,String error,RuntimePackage candidate,String provider,String raw,long head)throws Exception{
        var next=new PackageUiPatchJob(j.operationId(),world,j.ownerPlayerId(),j.agentId(),j.taskId(),j.taskIntentRevision(),j.base(),candidate,j.prompt(),state,error,provider,raw,head,j.revision()+1,clock.millis());
        if(!repo.compareAndSet(world,NS,j.operationId().toString(),j.revision(),json.writeValueAsString(next),clock.millis()).accepted())throw new IllegalStateException("UI_PATCH_CAS");jobs.put(j.operationId(),next);return next;
    }
    private static boolean libraryBudget(IllegalStateException e){return Set.of("PACKAGE_LIBRARY_HEAD_BUDGET","PACKAGE_LIBRARY_VERSION_BUDGET").contains(Objects.toString(e.getMessage(),""));}
    private static boolean matches(RuntimePackage a,RuntimePackage b,long revision){return a!=null&&b!=null&&a.packageId().equals(b.packageId())&&a.revision()==revision&&a.canonicalSha256().equals(b.canonicalSha256());}
    private void requireOpen(){if(closed)throw new IllegalStateException("UI_PATCH_SERVICE_CLOSED");}
    @Override public synchronized void close()throws Exception{if(!closed){closed=true;repo.close();}}
}
