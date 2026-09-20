package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.task.TaskMutationResult;
import dev.mineagent.runtime.api.task.TaskNodeStatus;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.api.task.TaskStep;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class TaskManager implements AutoCloseable {
    private static final String NAMESPACE = "tasks";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, ManagedTask> tasks = new LinkedHashMap<>();
    private final Map<UUID,ManagedTask> terminalCache=new LinkedHashMap<>(128,.75f,true);
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<ManagedTask>> changeListeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    public AutoCloseable onChange(java.util.function.Consumer<ManagedTask> listener){java.util.Objects.requireNonNull(listener);changeListeners.add(listener);return ()->changeListeners.remove(listener);}
    private void notifyChanged(ManagedTask task){for(var listener:changeListeners)try{listener.accept(task);}catch(RuntimeException failure){System.getLogger(TaskManager.class.getName()).log(System.Logger.Level.WARNING,"Task listener failed: "+failure.getClass().getSimpleName());}}

    private TaskManager(SqliteRuntimeRepository repository, UUID worldId, Clock clock) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        repository.initializeTaskRetention(worldId);
        for(int offset=0;;offset+=256){var page=repository.taskHistory(worldId,null,"ALL","ACTIVE",offset,256,null);for(var stored:page)cache(mapper.readValue(stored.payload(),ManagedTask.class));if(page.size()<256)break;}
    }

    public static TaskManager open(Path database, UUID worldId, Clock clock) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new TaskManager(repository, worldId, clock);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized ManagedTask create(
            UUID agentId,
            UUID ownerPlayerId,
            String title,
            int priority,
            List<TaskStepSpec> plan
    ) throws Exception {
        return createWithId(UUID.randomUUID(),agentId,ownerPlayerId,title,priority,plan,null);
    }
    public synchronized ManagedTask create(UUID agent,UUID owner,String title,int priority,List<TaskStepSpec> plan,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent)throws Exception{
        return createWithId(UUID.randomUUID(),agent,owner,title,priority,plan,parent);
    }
    public synchronized dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Entry budgetLineage(UUID task)throws Exception{return repository.taskLineage(worldId,task);}
    public synchronized ManagedTask createIdempotent(UUID operation,UUID agentId,UUID ownerPlayerId,String title,int priority,List<TaskStepSpec> plan)throws Exception{
        return createIdempotent(operation,agentId,ownerPlayerId,title,priority,plan,null);
    }
    public synchronized ManagedTask createIdempotent(UUID operation,UUID agentId,UUID ownerPlayerId,String title,int priority,List<TaskStepSpec> plan,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent)throws Exception{
        validatePlan(plan);
        if(operation==null||agentId==null||ownerPlayerId==null||title==null||title.isBlank()||title.codePointCount(0,title.length())>256||priority<0||priority>100)throw new IllegalArgumentException("invalid task");
        UUID taskId=startTaskId(ownerPlayerId,operation);
        var request=new java.util.TreeMap<String,Object>();request.put("agent",agentId);request.put("owner",ownerPlayerId);request.put("title",title.strip());request.put("priority",priority);
        request.put("plan",plan.stream().map(s->{var value=new java.util.TreeMap<String,Object>();value.put("id",s.stepId());value.put("dependencies",s.dependencies().stream().sorted().toList());return value;}).toList());
        if(parent!=null)request.put("budgetParent",List.of(parent.taskId(),parent.intent(),parent.relation()));
        String fingerprint=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(mapper.writeValueAsBytes(request));
        String payload=mapper.writeValueAsString(java.util.Map.of("fingerprint",fingerprint));var old=repository.getIncludingDeleted(worldId,"task_start_requests",taskId.toString());
        if(old.isPresent()){if(old.get().deleted())throw new IllegalStateException("TASK_CREATION_REMOVED");if(!old.get().payload().equals(payload))throw new IllegalArgumentException("TASK_OPERATION_REUSED");}
        else{
            var result=write("task_start_requests",taskId.toString(),0,payload,clock.millis());
            if(!result.accepted()&&!result.record().payload().equals(payload))throw new IllegalArgumentException("TASK_OPERATION_REUSED");
        }
        var existing=get(taskId).orElse(null);if(existing!=null){if(!existing.agentId().equals(agentId)||!existing.ownerPlayerId().equals(ownerPlayerId))throw new IllegalStateException("TASK_CREATION_CONTEXT");return existing;}
        return createWithId(taskId,agentId,ownerPlayerId,title,priority,plan,parent);
    }
    private ManagedTask createWithId(UUID taskId,UUID agentId,UUID ownerPlayerId,String title,int priority,List<TaskStepSpec> plan,dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent parent)throws Exception{
        validatePlan(plan);
        if (agentId == null || ownerPlayerId == null || title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 256 || priority < 0 || priority > 100) {
            throw new IllegalArgumentException("invalid task");
        }
        var steps = plan.stream().map(spec -> new TaskStep(
                spec.stepId(), spec.dependencies(), TaskNodeStatus.PENDING)).toList();
        var task = new ManagedTask(taskId, worldId, agentId, ownerPlayerId, title.strip(), priority,
                1, TaskStatus.RUNNING, steps, "创建任务", clock.millis());
        dev.mineagent.runtime.core.persistence.RuntimeCasResult stored;
        try{stored=repository.createTask(worldId,taskId,mapper.writeValueAsString(task),task.updatedAtEpochMillis(),parent);}
        catch(java.sql.SQLException failure){throw taskWriteFailure(failure);}
        if (!stored.accepted()) {
            throw new IllegalStateException("task id collision");
        }
        cache(task);
        return task;
    }

    public synchronized Optional<ManagedTask> get(UUID taskId) {
        java.util.Objects.requireNonNull(taskId);var cached=tasks.get(taskId);if(cached==null)cached=terminalCache.get(taskId);if(cached!=null)return Optional.of(cached);
        try{var stored=repository.get(worldId,NAMESPACE,taskId.toString());if(stored.isEmpty())return Optional.empty();var task=mapper.readValue(stored.get().payload(),ManagedTask.class);cache(task);return Optional.of(task);}catch(Exception unavailable){throw new IllegalStateException("TASK_READ_UNAVAILABLE",unavailable);}
    }
    private void cache(ManagedTask task){if(task.status()==TaskStatus.COMPLETED||task.status()==TaskStatus.CANCELLED){tasks.remove(task.taskId());terminalCache.put(task.taskId(),task);while(terminalCache.size()>128)terminalCache.remove(terminalCache.keySet().iterator().next());}else{terminalCache.remove(task.taskId());tasks.put(task.taskId(),task);}}
    private dev.mineagent.runtime.core.persistence.RuntimeCasResult write(String namespace,String id,long revision,String payload,long at)throws java.sql.SQLException{
        try{return repository.compareAndSet(worldId,namespace,id,revision,payload,at);}catch(java.sql.SQLException failure){throw taskWriteFailure(failure);}
    }
    private static java.sql.SQLException taskWriteFailure(java.sql.SQLException failure){
        String message=java.util.Objects.toString(failure.getMessage(),"");
        for(String code:List.of("TASK_RETAINED_ROW_BUDGET","TASK_RETAINED_BYTE_BUDGET","TASK_ACTIVE_BUDGET","TASK_START_LEDGER_FULL","TASK_BUDGET_TASK_MISSING","TASK_BUDGET_PARENT_CONTEXT","TASK_BUDGET_PARENT_CHANGED","TASK_BUDGET_LINEAGE_DEPTH"))if(message.contains(code))throw new IllegalStateException(code,failure);
        return failure;
    }
    public UUID startTaskId(UUID owner,UUID operation){return UUID.nameUUIDFromBytes(("task-start|"+worldId+"|"+java.util.Objects.requireNonNull(owner)+"|"+java.util.Objects.requireNonNull(operation)).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public record ReplanSource(String fingerprint,ManagedTask original,String goal,String note){}
    public synchronized Optional<ReplanSource> replanSource(UUID taskId)throws Exception{
        var row=repository.getIncludingDeleted(worldId,"task_replan_sources",taskId.toString());if(row.isPresent()&&row.get().deleted())throw new IllegalStateException("TASK_REPLAN_SOURCE_REMOVED");return row.isEmpty()?Optional.empty():Optional.of(mapper.readValue(row.get().payload(),ReplanSource.class));
    }
    /** An explicitly requested fresh plan. The old task, intent and effects remain immutable. */
    public synchronized ManagedTask createReplanned(UUID operation,UUID owner,UUID sourceId,long expectedRevision,String goal,String note)throws Exception{
        var current=require(sourceId);if(!current.ownerPlayerId().equals(owner))throw new SecurityException("TASK_OWNER");
        if(goal==null||goal.isBlank()||goal.strip().codePointCount(0,goal.strip().length())>256||note==null||note.length()>2048)throw new IllegalArgumentException("REPLAN_INPUT_INVALID");
        UUID id=startTaskId(owner,operation);String fingerprint=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(mapper.writeValueAsBytes(List.of(sourceId,expectedRevision,goal.strip(),note.strip(),owner)));
        var stored=replanSource(id);
        if(stored.isPresent()&&!stored.get().fingerprint().equals(fingerprint))throw new IllegalArgumentException("TASK_OPERATION_REUSED");
        var existing=get(id).orElse(null);if(stored.isPresent()&&existing!=null)return existing;
        if(existing!=null)throw new IllegalArgumentException("TASK_OPERATION_REUSED");
        if(current.revision()!=expectedRevision)throw new IllegalStateException("STALE_TASK_REVISION");
        if(current.status()!=TaskStatus.PAUSED&&current.status()!=TaskStatus.FAILED)throw new IllegalStateException("TASK_REPLAN_NOT_PAUSED");
        if(current.steps().stream().noneMatch(s->s.stepId().equals("plan")||s.stepId().equals("replan")))throw new IllegalStateException("TASK_REPLAN_UNSUPPORTED");
        if(stored.isEmpty()){
            var source=new ReplanSource(fingerprint,current,goal.strip(),note.strip());
            if(!write("task_replan_sources",id.toString(),0,mapper.writeValueAsString(source),clock.millis()).accepted())throw new IllegalStateException("TASK_REPLAN_CONFLICT");
        }
        return createIdempotent(operation,current.agentId(),owner,goal.strip(),current.priority(),List.of(new TaskStepSpec("replan",Set.of()),new TaskStepSpec("execute",Set.of("replan"))),dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent.of(current,"REPLAN"));
    }
    /** Sticky, durable invalidation of this intent, including in-flight planning and old question links. */
    public synchronized TaskMutationResult revokeAuthority(UUID taskId,long expectedRevision)throws Exception{
        var current=require(taskId);if(current.revision()!=expectedRevision)return TaskMutationResult.rejected(current,"STALE_REVISION");
        if(TaskAuthorityFence.revoked(current))return TaskMutationResult.accepted(current);
        if(current.status()!=TaskStatus.RUNNING&&current.status()!=TaskStatus.PAUSED)return TaskMutationResult.rejected(current,"TASK_NOT_ACTIVE");
        return save(current,copy(current,current.revision()+1,TaskStatus.PAUSED,current.steps(),TaskAuthorityFence.REVOKED,clock.millis(),true));
    }

    public synchronized List<ManagedTask> all() {
        try{var result=new ArrayList<ManagedTask>();for(var row:repository.list(worldId,NAMESPACE))result.add(mapper.readValue(row.payload(),ManagedTask.class));return List.copyOf(result);}catch(Exception unavailable){throw new IllegalStateException("TASK_HISTORY_UNAVAILABLE",unavailable);}
    }
    /** Only nonterminal work belongs in a per-tick scheduler scan. */
    public synchronized List<ManagedTask> active(){return tasks.values().stream().sorted(java.util.Comparator.comparingLong(ManagedTask::updatedAtEpochMillis).reversed()).toList();}
    public synchronized long totalCount(){try{return repository.taskCount(worldId,null,"ALL","ALL");}catch(Exception unavailable){throw new IllegalStateException("TASK_HISTORY_UNAVAILABLE",unavailable);}}
    public synchronized dev.mineagent.runtime.core.persistence.TaskRetention.Usage usage(UUID owner)throws Exception{return repository.taskUsage(worldId,java.util.Objects.requireNonNull(owner));}
    public record HistoryPage(long total,int offset,int nextOffset,boolean more,List<ManagedTask> tasks){public HistoryPage{tasks=List.copyOf(tasks);}}
    public synchronized HistoryPage history(UUID owner,String state,String archive,int offset,int limit)throws Exception{
        java.util.Objects.requireNonNull(owner);if(limit>16)throw new IllegalArgumentException("TASK_HISTORY_PAGE");var result=new ArrayList<ManagedTask>();for(var row:repository.taskHistory(worldId,owner,state,archive,offset,limit,null))result.add(mapper.readValue(row.payload(),ManagedTask.class));long total=repository.taskCount(worldId,owner,state,archive);return new HistoryPage(total,offset,offset+result.size(),total>offset+result.size(),result);
    }
    public synchronized List<ManagedTask> recentWorld(UUID owner,Set<UUID> related,int limit)throws Exception{var result=new ArrayList<ManagedTask>();for(var row:repository.taskHistory(worldId,owner,"ALL","ALL",0,limit,Set.copyOf(related)))result.add(mapper.readValue(row.payload(),ManagedTask.class));return List.copyOf(result);}

    public synchronized TaskMutationResult pauseForServiceBudget(UUID taskId,long expectedRevision,String code)throws Exception{
        if(!dev.mineagent.runtime.core.config.ServiceCallBudget.ERRORS.contains(code))throw new IllegalArgumentException("TASK_BUDGET_ERROR_CODE");
        var current=require(taskId);
        if(current.revision()!=expectedRevision)return TaskMutationResult.rejected(current,"STALE_REVISION");
        if(current.status()!=TaskStatus.RUNNING)return TaskMutationResult.rejected(current,"TASK_NOT_RUNNING");
        return save(current,copy(current,current.revision()+1,TaskStatus.PAUSED,current.steps(),code,clock.millis()));
    }

    public synchronized TaskMutationResult transition(
            UUID taskId,
            long expectedRevision,
            boolean authorized,
            TaskStatus target
    ) throws Exception {
        ManagedTask current = require(taskId);
        if (!authorized) {
            return TaskMutationResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return TaskMutationResult.rejected(current, "STALE_REVISION");
        }
        if(target==TaskStatus.RUNNING&&TaskAuthorityFence.revoked(current))return TaskMutationResult.rejected(current,"TASK_REPLAN_REQUIRED");
        if (!allowedTransition(current.status(), target)) {
            return TaskMutationResult.rejected(current, "INVALID_TRANSITION");
        }
        ManagedTask next = copy(current, current.revision() + 1, target, current.steps(),
                "状态变更: " + target, clock.millis(), target == TaskStatus.CANCELLED);
        return save(current, next);
    }

    public synchronized TaskMutationResult replan(
            UUID taskId,
            long expectedRevision,
            boolean authorized,
            List<TaskStepSpec> plan,
            String reason
    ) throws Exception {
        ManagedTask current = require(taskId);
        if (!authorized) {
            return TaskMutationResult.rejected(current, "FORBIDDEN");
        }
        if (current.revision() != expectedRevision) {
            return TaskMutationResult.rejected(current, "STALE_REVISION");
        }
        if (current.status() == TaskStatus.CANCELLED || current.status() == TaskStatus.COMPLETED) {
            return TaskMutationResult.rejected(current, "INVALID_TRANSITION");
        }
        validatePlan(plan);
        if (reason == null || reason.isBlank()) {
            return TaskMutationResult.rejected(current, "INVALID_REASON");
        }
        List<TaskStep> steps = plan.stream().map(spec ->
                new TaskStep(spec.stepId(), spec.dependencies(), TaskNodeStatus.PENDING)).toList();
        ManagedTask next = copy(current, current.revision() + 1, TaskStatus.RUNNING,
                steps, reason.strip(), clock.millis(), true);
        return save(current, next);
    }

    public synchronized TaskMutationResult completeStep(
            UUID taskId,
            long expectedRevision,
            String stepId
    ) throws Exception {
        ManagedTask current = require(taskId);
        if (current.revision() != expectedRevision) {
            return TaskMutationResult.rejected(current, "STALE_REVISION");
        }
        if (current.status() != TaskStatus.RUNNING) {
            return TaskMutationResult.rejected(current, "TASK_NOT_RUNNING");
        }
        TaskStep target = current.steps().stream()
                .filter(step -> step.stepId().equals(stepId)).findFirst().orElse(null);
        if (target == null) {
            return TaskMutationResult.rejected(current, "UNKNOWN_STEP");
        }
        if (target.status() != TaskNodeStatus.PENDING) {
            return TaskMutationResult.rejected(current, "STEP_NOT_PENDING");
        }
        Set<String> completed = current.steps().stream()
                .filter(step -> step.status() == TaskNodeStatus.COMPLETED)
                .map(TaskStep::stepId).collect(java.util.stream.Collectors.toSet());
        if (!completed.containsAll(target.dependencies())) {
            return TaskMutationResult.rejected(current, "DEPENDENCY_NOT_COMPLETE");
        }
        var steps = new ArrayList<TaskStep>(current.steps().size());
        for (TaskStep step : current.steps()) {
            steps.add(step.stepId().equals(stepId)
                    ? new TaskStep(step.stepId(), step.dependencies(), TaskNodeStatus.COMPLETED)
                    : step);
        }
        TaskStatus status = steps.stream().allMatch(step -> step.status() == TaskNodeStatus.COMPLETED)
                ? TaskStatus.COMPLETED : TaskStatus.RUNNING;
        ManagedTask next = copy(current, current.revision() + 1, status, steps,
                "完成步骤: " + stepId, clock.millis());
        return save(current, next);
    }

    public synchronized TaskMutationResult decisionNodes(UUID taskId, long expectedRevision, Set<String> nodeIds,
                                                        boolean waiting, String reason) throws Exception {
        ManagedTask current = require(taskId);
        if (current.revision() != expectedRevision) return TaskMutationResult.rejected(current, "STALE_REVISION");
        if (current.status() != TaskStatus.RUNNING) return TaskMutationResult.rejected(current, "TASK_NOT_RUNNING");
        if (nodeIds == null || nodeIds.isEmpty() || reason == null || reason.length() > 16_384)
            return TaskMutationResult.rejected(current, "INVALID_DECISION_NODES");
        TaskNodeStatus expected = waiting ? TaskNodeStatus.PENDING : TaskNodeStatus.WAITING_FOR_PLAYER;
        if (current.steps().stream().filter(s -> nodeIds.contains(s.stepId()) && s.status() == expected).count() != nodeIds.size())
            return TaskMutationResult.rejected(current, "STALE_DECISION_NODES");
        var steps = current.steps().stream().map(s -> nodeIds.contains(s.stepId())
                ? new TaskStep(s.stepId(), s.dependencies(), waiting ? TaskNodeStatus.WAITING_FOR_PLAYER : TaskNodeStatus.PENDING) : s).toList();
        return save(current, copy(current, current.revision() + 1, current.status(), steps, reason, clock.millis()));
    }

    private TaskMutationResult save(ManagedTask current, ManagedTask next) throws Exception {
        var result = write(NAMESPACE, current.taskId().toString(),
                current.revision(), mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!result.accepted()) {
            ManagedTask latest = mapper.readValue(result.record().payload(), ManagedTask.class);
            cache(latest);
            notifyChanged(latest);
            return TaskMutationResult.rejected(latest, "STALE_REVISION");
        }
        cache(next);
        notifyChanged(next);
        return TaskMutationResult.accepted(next);
    }

    private ManagedTask require(UUID taskId) {
        ManagedTask task = get(taskId).orElse(null);
        if (task == null) {
            throw new IllegalArgumentException("unknown task");
        }
        return task;
    }

    private static ManagedTask copy(
            ManagedTask task,
            long revision,
            TaskStatus status,
            List<TaskStep> steps,
            String reason,
            long updatedAt
    ) {
        return copy(task, revision, status, steps, reason, updatedAt, false);
    }

    private static ManagedTask copy(ManagedTask task, long revision, TaskStatus status, List<TaskStep> steps,
                                     String reason, long updatedAt, boolean newIntent) {
        return new ManagedTask(task.taskId(), task.worldId(), task.agentId(), task.ownerPlayerId(),
                task.title(), task.priority(), revision, status, steps, reason, updatedAt,
                task.intentRevision() + (newIntent ? 1 : 0));
    }

    private static boolean allowedTransition(TaskStatus current, TaskStatus target) {
        return switch (current) {
            case RUNNING -> target == TaskStatus.PAUSED || target == TaskStatus.CANCELLED;
            case PAUSED, WAITING_FOR_PLAYER -> target == TaskStatus.RUNNING || target == TaskStatus.CANCELLED;
            case FAILED -> target == TaskStatus.RUNNING || target == TaskStatus.CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    private static void validatePlan(List<TaskStepSpec> plan) {
        if (plan == null || plan.isEmpty() || plan.size() > 1_000) {
            throw new IllegalArgumentException("invalid task plan");
        }
        var byId = new LinkedHashMap<String, TaskStepSpec>();
        for (TaskStepSpec step : plan) {
            if (step == null || step.stepId() == null || !step.stepId().matches("[a-z][a-z0-9_]{0,63}")
                    || byId.putIfAbsent(step.stepId(), step) != null) {
                throw new IllegalArgumentException("invalid or duplicate task step");
            }
        }
        for (TaskStepSpec step : plan) {
            if (!byId.keySet().containsAll(step.dependencies())) {
                throw new IllegalArgumentException("unknown task dependency");
            }
        }
        var visiting = new HashSet<String>();
        var visited = new HashSet<String>();
        for (String id : byId.keySet()) {
            if (cycle(id, byId, visiting, visited)) {
                throw new IllegalArgumentException("task dependency cycle");
            }
        }
    }

    private static boolean cycle(
            String id,
            Map<String, TaskStepSpec> plan,
            Set<String> visiting,
            Set<String> visited
    ) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        for (String dependency : plan.get(id).dependencies()) {
            if (cycle(dependency, plan, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    @Override
    public synchronized void close() throws Exception {
        changeListeners.clear();
        repository.close();
    }
}
