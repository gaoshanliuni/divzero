package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.PackageOrigin;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.task.TaskManager;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Durable, owner-scoped generation admission and commit fence. No provider calls or automatic billing retries here.
 * Platform calls all mutations on its server thread, together with the last live permission check.
 * Incomplete multi-record publication after a crash remains disabled and unavailable, never replayed automatically.
 */
public final class PackageGenerationService implements AutoCloseable {
    private static final String NAMESPACE = "package_generation_jobs";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final TaskManager tasks;
    private final RuntimePackageLibrary library;
    private final ObjectMapper json = new ObjectMapper();
    private final dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs<PackageGenerationJob> jobs;
    private boolean closed;
    public record Submission(PackageGenerationJob job, boolean duplicate) {}

    private PackageGenerationService(SqliteRuntimeRepository repository, UUID worldId, Clock clock,
                                     TaskManager tasks, RuntimePackageLibrary library) throws Exception {
        this.repository = repository; this.worldId = worldId; this.clock = clock; this.tasks = tasks; this.library = library;
        repository.initializePackageJobRetention(worldId);
        jobs=new dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs<>(repository,worldId,NAMESPACE,PackageGenerationJob.class,j->j.state().equals("GENERATING"),PackageGenerationJob::operationId,PackageGenerationJob::worldId,PackageGenerationJob::revision);
        jobs.loadActive();
        for (var job : List.copyOf(jobs.values())) if (job.state().equals("GENERATING")) {
            pauseIfCurrent(job);
            save(job, "INTERRUPTED", "SERVER_RESTARTED", "", "", "");
        }
    }
    public static PackageGenerationService open(Path database, UUID worldId, Clock clock,
                                                TaskManager tasks, RuntimePackageLibrary library) throws Exception {
        var repo = new SqliteRuntimeRepository(database);
        try { return new PackageGenerationService(repo, worldId, clock, tasks, library); }
        catch (Exception failure) { repo.close(); throw failure; }
    }
    public synchronized Submission submit(UUID owner, UUID agent, UUID operationId, String prompt, boolean authorized) throws Exception {
        return submit(owner,agent,operationId,prompt,authorized,"UI_PACKAGE");
    }
    public synchronized Submission submit(UUID owner, UUID agent, UUID operationId, String prompt, boolean authorized,String purpose) throws Exception {
        return submit(owner,agent,operationId,prompt,authorized,purpose,null,null,null);
    }
    public synchronized Submission submit(UUID owner,UUID agent,UUID operation,String prompt,boolean authorized,String purpose,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent)throws Exception{
        return submit(owner,agent,operation,prompt,authorized,purpose,null,parent,null);
    }
    public synchronized Submission submit(UUID owner,UUID agent,UUID operation,String prompt,boolean authorized,String purpose,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent,dev.mineagent.runtime.core.compile.NativeCoderContext nativeSelection)throws Exception{
        return submit(owner,agent,operation,prompt,authorized,purpose,null,parent,nativeSelection);
    }
    public synchronized Submission submitRepair(UUID owner,UUID agent,UUID operationId,UUID sourceOperation,long sourceRevision,
            String rawHash,String prompt,boolean authorized,boolean confirmed)throws Exception{
        requireOpen();if(!authorized||!confirmed)throw new SecurityException("GENERATION_REPAIR_CONSENT_REQUIRED");
        var source=jobs.get(sourceOperation);
        if(source==null||!source.ownerPlayerId().equals(owner)||!source.agentId().equals(agent))throw new SecurityException("GENERATION_REPAIR_SOURCE_OWNER");
        if(sourceOperation.equals(operationId)||!source.state().equals("FAILED")||source.revision()!=sourceRevision
                ||!source.rawOutputSha256().matches("[a-f0-9]{64}")||!source.rawOutputSha256().equals(rawHash))throw new IllegalArgumentException("GENERATION_REPAIR_SOURCE_CHANGED");
        if(library.get(source.packageId()).isPresent())throw new IllegalStateException("GENERATION_REPAIR_SOURCE_INSTALLED");
        var repair=new GenerationRepairSource(source.operationId(),source.revision(),source.rawOutputSha256(),
                source.repairSource()==null?source.prompt():source.repairSource().originalPrompt(),source.errorCode());
        return submit(owner,agent,operationId,prompt,true,source.purpose(),repair,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent.of(tasks.get(source.taskId()).orElseThrow(),"REPAIR"),source.nativeSelection());
    }
    private Submission submit(UUID owner,UUID agent,UUID operationId,String prompt,boolean authorized,String purpose,GenerationRepairSource repair,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent,dev.mineagent.runtime.core.compile.NativeCoderContext nativeSelection) throws Exception {
        requireOpen();
        if(!Set.of("UI_PACKAGE","WORLD_CONTENT").contains(purpose))throw new IllegalArgumentException("GENERATION_PURPOSE");
        if (!authorized) throw new SecurityException("PERMISSION_DENIED");
        Objects.requireNonNull(owner); Objects.requireNonNull(agent); Objects.requireNonNull(operationId);
        if (prompt == null || prompt.isBlank() || prompt.length() > 8192) throw new IllegalArgumentException("INVALID_GENERATION_PROMPT");
        prompt = prompt.strip();
        var old = jobs.get(operationId);
        if (old != null) {
            if (!old.ownerPlayerId().equals(owner)) throw new SecurityException("GENERATION_OWNER_MISMATCH");
            if (!old.agentId().equals(agent) || !old.prompt().equals(prompt)||!old.purpose().equals(purpose)||!Objects.equals(old.repairSource(),repair)||!Objects.equals(old.nativeSelection(),nativeSelection)) throw new IllegalArgumentException("OPERATION_ID_REUSED");
            return new Submission(old, true);
        }
        // Global retained rows/bytes are enforced atomically before this job may dispatch.
        var pending = jobs.values().stream().filter(j -> j.state().equals("GENERATING")).toList();
        if (pending.size() >= 4 || pending.stream().anyMatch(j -> j.ownerPlayerId().equals(owner)))
            throw new IllegalStateException("GENERATION_BUSY");
        String title = prompt.substring(0, prompt.offsetByCodePoints(0, Math.min(80, prompt.codePointCount(0, prompt.length()))));
        String generate=purpose.equals("WORLD_CONTENT")?"generate_content":"generate_ui",publish=purpose.equals("WORLD_CONTENT")?"publish_content":"publish_ui";
        var task = tasks.create(agent, owner, (purpose.equals("WORLD_CONTENT")?"内容生成: ":"网页生成: ") + title, 60, List.of(
                new TaskStepSpec(generate, Set.of()), new TaskStepSpec(publish, Set.of(generate))),parent);
        var job = new PackageGenerationJob(operationId, worldId, owner, agent, task.taskId(), task.intentRevision(),
                UUID.randomUUID(), 1, prompt, "GENERATING", "", "", "", "", 1, clock.millis(),purpose,repair,nativeSelection,null);
        try {
            var saved = repository.compareAndSet(worldId, NAMESPACE, operationId.toString(), 0, json.writeValueAsString(job), clock.millis());
            if (!saved.accepted()) throw new IllegalStateException("GENERATION_CAS_CONFLICT");
        } catch (Exception failure) {
            tasks.transition(task.taskId(), task.revision(), true, TaskStatus.CANCELLED);
            throw failure;
        }
        jobs.put(operationId, job);
        return new Submission(job, false);
    }
    public synchronized PackageGenerationJob bindNativeContext(PackageGenerationJob ticket,dev.mineagent.runtime.core.compile.NativeCoderContext.Snapshot context)throws Exception{
        requireOpen();var job=requireTicket(ticket);if(job.nativeSelection()==null||context==null||!context.selectionHash().equals(job.nativeSelection().fingerprint())||!job.state().equals("GENERATING"))throw new IllegalStateException("GENERATION_NATIVE_CONTEXT");
        if(job.nativeContext()!=null){if(!job.nativeContext().equals(context))throw new IllegalStateException("GENERATION_NATIVE_CONTEXT_CHANGED");return job;}
        var next=new PackageGenerationJob(job.operationId(),job.worldId(),job.ownerPlayerId(),job.agentId(),job.taskId(),job.taskIntentRevision(),job.packageId(),job.packageRevision(),job.prompt(),job.state(),job.errorCode(),job.providerId(),job.rawOutputSha256(),job.canonicalSha256(),job.revision()+1,clock.millis(),job.purpose(),job.repairSource(),job.nativeSelection(),context);
        var saved=repository.compareAndSet(worldId,NAMESPACE,job.operationId().toString(),job.revision(),json.writeValueAsString(next),clock.millis());if(!saved.accepted())throw new IllegalStateException("GENERATION_CAS_CONFLICT");jobs.put(job.operationId(),next);return next;
    }
    public synchronized PackageGenerationJob complete(PackageGenerationJob ticket, RuntimePackage candidate,
            String providerId, String rawHash, boolean authorized) throws Exception {
        requireOpen();
        var current = requireTicket(ticket);
        if (!current.state().equals("GENERATING")) return current;
        if (!authorized) return fail(ticket, "PERMISSION_DENIED");
        if (!currentTask(ticket)) return save(current, "STALE", "STALE_TASK", "", "", "");
        if (candidate == null || !candidate.packageId().equals(ticket.packageId()) || candidate.revision() != ticket.packageRevision()
                || candidate.enabled() || candidate.origin() != PackageOrigin.GENERATED
                || rawHash == null || !rawHash.matches("[0-9a-f]{64}") || providerId == null || !providerId.matches("[a-zA-Z0-9_.-]{1,128}"))
            return fail(ticket, "PACKAGE_CONTEXT_MISMATCH");
        if (library.get(ticket.packageId()).isPresent()) return fail(ticket, "STALE_PACKAGE");
        var installed = library.install(candidate);
        if (!installed.accepted()) return fail(ticket, installed.errorCode());
        var task = tasks.get(ticket.taskId()).orElseThrow();
        var generated = tasks.completeStep(task.taskId(), task.revision(), ticket.generateStep());
        if (!generated.accepted()) return fail(ticket, "TASK_COMMIT_FAILED");
        var published = tasks.completeStep(task.taskId(), generated.task().revision(), ticket.publishStep());
        if (!published.accepted()) return fail(ticket, "TASK_COMMIT_FAILED");
        return save(current, "PUBLISHED", "", providerId, rawHash, candidate.canonicalSha256());
    }
    public synchronized PackageGenerationJob fail(PackageGenerationJob ticket,String errorCode)throws Exception{return fail(ticket,errorCode,"","");}
    public synchronized PackageGenerationJob fail(PackageGenerationJob ticket, String errorCode,String providerId,String rawHash) throws Exception {
        requireOpen();
        if(providerId==null||!providerId.isEmpty()&&!providerId.matches("[A-Za-z0-9_.-]{1,128}")||rawHash==null||!rawHash.isEmpty()&&!rawHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("GENERATION_FAILURE_EVIDENCE");
        var job = requireTicket(ticket);
        if (!job.state().equals("GENERATING")) return job;
        if (!currentTask(ticket)) return save(job, "STALE", "STALE_TASK", "", "", "");
        pauseIfCurrent(job);
        String code = errorCode != null && errorCode.matches("[A-Z][A-Z0-9_]{0,63}") ? errorCode : "GENERATION_FAILED";
        return save(job, "FAILED", code, providerId, rawHash, "");
    }
    public synchronized PackageGenerationJob cancel(UUID owner, UUID operationId) throws Exception {
        requireOpen();
        var job = jobs.get(operationId);
        if (job == null || !job.ownerPlayerId().equals(owner)) throw new SecurityException("GENERATION_OWNER_MISMATCH");
        if (!job.state().equals("GENERATING")) return job;
        if (currentTask(job)) {
            var task = tasks.get(job.taskId()).orElseThrow();
            var cancelled = tasks.transition(task.taskId(), task.revision(), true, TaskStatus.CANCELLED);
            if (!cancelled.accepted()) throw new IllegalStateException("TASK_CANCEL_FAILED");
        }
        return save(job, "CANCELLED", "USER_CANCELLED", "", "", "");
    }
    /** A late verified Worker result can be retained, but cannot change a terminal state or install a package. */
    public synchronized PackageGenerationJob recordOutputEvidence(PackageGenerationJob ticket,String provider,String rawHash)throws Exception{
        requireOpen();var job=requireTicket(ticket);
        if(provider==null||!provider.matches("[A-Za-z0-9_.-]{1,128}")||rawHash==null||!rawHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("GENERATION_FAILURE_EVIDENCE");
        if(job.state().equals("GENERATING"))throw new IllegalStateException("GENERATION_NOT_TERMINAL");
        if(!job.rawOutputSha256().isEmpty()){
            if(!job.rawOutputSha256().equals(rawHash)||!job.providerId().equals(provider))throw new IllegalArgumentException("GENERATION_EVIDENCE_CHANGED");
            return job;
        }
        return save(job,job.state(),job.errorCode(),provider,rawHash,job.canonicalSha256());
    }
    public synchronized List<PackageGenerationJob> list(UUID owner) {
        requireOpen();
        return jobs.page(dev.mineagent.runtime.core.persistence.PackageJobRetention.Query.all(owner),0,64);
    }
    public synchronized dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs.Page<PackageGenerationJob> history(UUID owner,String state,String archive,int offset){requireOpen();return jobs.history(owner,state,archive,offset,8);}
    public synchronized Optional<PackageGenerationJob> find(UUID owner,UUID operation){requireOpen();var job=jobs.get(operation);if(job!=null&&!job.ownerPlayerId().equals(owner))throw new SecurityException("GENERATION_OWNER_MISMATCH");return Optional.ofNullable(job);}
    public synchronized boolean current(PackageGenerationJob ticket){requireOpen();var job=requireTicket(ticket);return job.state().equals("GENERATING")&&currentTask(ticket);}
    public synchronized boolean ownsPublished(UUID owner, UUID packageId, long revision) {
        requireOpen();
        var head=library.get(packageId).orElse(null);if(head==null||head.revision()!=revision)return false;
        try{return repository.packageJobOwnedHead(worldId,NAMESPACE,owner,packageId,revision,head.canonicalSha256());}
        catch(java.sql.SQLException failure){throw new IllegalStateException("PACKAGE_JOB_READ_UNAVAILABLE",failure);}
    }
    private PackageGenerationJob requireTicket(PackageGenerationJob ticket) {
        var job = jobs.get(ticket.operationId());
        if (job == null || !job.worldId().equals(ticket.worldId()) || !job.taskId().equals(ticket.taskId())
                || !job.ownerPlayerId().equals(ticket.ownerPlayerId()) || !job.agentId().equals(ticket.agentId())
                || job.taskIntentRevision() != ticket.taskIntentRevision() || !job.packageId().equals(ticket.packageId())
                || job.packageRevision() != ticket.packageRevision() || !job.prompt().equals(ticket.prompt())||!job.purpose().equals(ticket.purpose())||!Objects.equals(job.repairSource(),ticket.repairSource())||!Objects.equals(job.nativeSelection(),ticket.nativeSelection()))
            throw new IllegalArgumentException("GENERATION_TICKET_MISMATCH");
        return job;
    }
    private boolean currentTask(PackageGenerationJob job) {
        ManagedTask task = tasks.get(job.taskId()).orElse(null);
        return task != null && task.worldId().equals(worldId) && task.agentId().equals(job.agentId())
                && task.ownerPlayerId().equals(job.ownerPlayerId()) && task.intentRevision() == job.taskIntentRevision()
                && task.status() == TaskStatus.RUNNING && task.runnableStepIds().contains(job.generateStep());
    }
    private void pauseIfCurrent(PackageGenerationJob job) throws Exception {
        if (!currentTask(job)) return;
        var task = tasks.get(job.taskId()).orElseThrow();
        if (!tasks.transition(task.taskId(), task.revision(), true, TaskStatus.PAUSED).accepted()) throw new IllegalStateException("TASK_PAUSE_FAILED");
    }
    private PackageGenerationJob save(PackageGenerationJob job, String state, String error, String provider, String raw, String canonical) throws Exception {
        var next = new PackageGenerationJob(job.operationId(), job.worldId(), job.ownerPlayerId(), job.agentId(), job.taskId(), job.taskIntentRevision(),
                job.packageId(), job.packageRevision(), job.prompt(), state, error, provider, raw, canonical, job.revision() + 1, clock.millis(),job.purpose(),job.repairSource(),job.nativeSelection(),job.nativeContext());
        var saved = repository.compareAndSet(worldId, NAMESPACE, job.operationId().toString(), job.revision(), json.writeValueAsString(next), clock.millis());
        if (!saved.accepted()) throw new IllegalStateException("GENERATION_CAS_CONFLICT");
        jobs.put(job.operationId(), next);
        return next;
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("GENERATION_SERVICE_CLOSED"); }
    @Override public synchronized void close() throws Exception { if (!closed) { closed = true; repository.close(); } }
}
