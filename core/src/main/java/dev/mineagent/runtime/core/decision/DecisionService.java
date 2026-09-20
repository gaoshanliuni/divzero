package dev.mineagent.runtime.core.decision;

import dev.mineagent.runtime.api.decision.DecisionAnswerSubmission;
import dev.mineagent.runtime.api.decision.DecisionRequest;
import dev.mineagent.runtime.api.decision.DecisionStatus;
import dev.mineagent.runtime.api.decision.DecisionSubmitResult;
import dev.mineagent.runtime.api.decision.DecisionTransitionResult;
import dev.mineagent.runtime.api.decision.SelectionMode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DecisionService implements AutoCloseable {
    private final Map<UUID, DecisionRequest> decisions = new HashMap<>();
    private final Map<UUID, AcceptedSubmission> submissions = new HashMap<>();
    private final Map<UUID, Long> storedRevisions = new HashMap<>();
    private final Map<UUID, TaskLink> taskLinks = new HashMap<>();
    private final Map<UUID,Map<String,String>> domainContexts=new HashMap<>();
    private final Map<UUID,DomainEffect> domainEffects=new HashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository repository;
    private UUID worldId;
    public DecisionService() {}
    public static DecisionService open(java.nio.file.Path database, UUID worldId) throws Exception {
        var service = new DecisionService();
        service.worldId = java.util.Objects.requireNonNull(worldId);
        service.repository = new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(database);
        try {
            for (var stored : service.repository.list(worldId, "decisions_v1")) service.load(stored);
            for(var entry:java.util.List.copyOf(service.domainEffects.entrySet()))if(entry.getValue().state().equals("APPLYING")){
                var old=entry.getValue();service.domainEffects.put(entry.getKey(),new DomainEffect(old.submissionId(),"INTERRUPTED","NATIVE_OUTCOME_UNKNOWN",old.resultRevision()));
                if(!service.persist(service.decisions.get(entry.getKey()),service.acceptedAnswer(entry.getKey()).orElse(null)))throw new IllegalStateException("DECISION_EFFECT_RECOVERY_FAILED");
            }
            return service;
        } catch (Exception failure) { service.repository.close(); throw failure; }
    }
    public record TaskLink(UUID taskId, long taskRevision, java.util.Set<String> nodeIds, String state, long intentRevision) {
        public TaskLink { nodeIds = java.util.Set.copyOf(nodeIds); if(intentRevision<1)intentRevision=taskRevision; }
    }
    public record DomainEffect(UUID submissionId,String state,String error,long resultRevision){}
    public record SavedDecision(DecisionRequest request, DecisionAnswerSubmission acceptedSubmission, TaskLink taskLink,Map<String,String> domainContext,DomainEffect domainEffect) {
        public SavedDecision(DecisionRequest request,DecisionAnswerSubmission acceptedSubmission,TaskLink taskLink){this(request,acceptedSubmission,taskLink,Map.of(),null);}
    }
    private void load(dev.mineagent.runtime.core.persistence.RuntimeRecord stored) throws Exception {
        SavedDecision saved = json.readValue(stored.payload(), SavedDecision.class);
        decisions.put(saved.request().decisionId(), saved.request());
        storedRevisions.put(saved.request().decisionId(), stored.revision());
        if (saved.taskLink() != null) taskLinks.put(saved.request().decisionId(), saved.taskLink());
        domainContexts.remove(saved.request().decisionId());domainEffects.remove(saved.request().decisionId());
        if(saved.domainContext()!=null&&!saved.domainContext().isEmpty())domainContexts.put(saved.request().decisionId(),Map.copyOf(saved.domainContext()));
        if(saved.domainEffect()!=null)domainEffects.put(saved.request().decisionId(),saved.domainEffect());
        if (saved.acceptedSubmission() != null) submissions.put(saved.acceptedSubmission().submissionId(),
                new AcceptedSubmission(saved.acceptedSubmission(), saved.request()));
    }
    private boolean persist(DecisionRequest request, DecisionAnswerSubmission acceptedSubmission) {
        if (repository == null) return true;
        try {
            var result = repository.compareAndSet(worldId, "decisions_v1", request.decisionId().toString(),
                    storedRevisions.getOrDefault(request.decisionId(), 0L), json.writeValueAsString(new SavedDecision(request, acceptedSubmission, taskLinks.get(request.decisionId()),domainContext(request.decisionId()),domainEffects.get(request.decisionId()))),
                    System.currentTimeMillis());
            if (!result.accepted()) { load(result.record()); return false; }
            storedRevisions.put(request.decisionId(), result.record().revision());
            return true;
        } catch (Exception failure) { throw new IllegalStateException("DECISION_PERSISTENCE_FAILED", failure); }
    }

    public synchronized void open(DecisionRequest request) {
        if (request.status() != DecisionStatus.OPEN) {
            throw new IllegalArgumentException("new decision must be OPEN");
        }
        if (decisions.containsKey(request.decisionId())) {
            throw new IllegalArgumentException("decisionId already exists");
        }
        if (!persist(request, null)) throw new IllegalStateException("DECISION_ALREADY_STORED");
        decisions.put(request.decisionId(), request);
    }
    public synchronized void open(DecisionRequest request,Map<String,String> context){
        if(context==null||context.isEmpty()||context.size()>16||context.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||e.getKey().length()>80||e.getValue().length()>8192))throw new IllegalArgumentException("DECISION_DOMAIN_CONTEXT");
        if(decisions.containsKey(request.decisionId()))throw new IllegalArgumentException("decisionId already exists");
        domainContexts.put(request.decisionId(),Map.copyOf(context));try{open(request);}catch(RuntimeException failure){if(!decisions.containsKey(request.decisionId()))domainContexts.remove(request.decisionId());throw failure;}
    }
    public synchronized Map<String,String> domainContext(UUID id){return domainContexts.getOrDefault(id,Map.of());}
    public synchronized Optional<DomainEffect> domainEffect(UUID id){return Optional.ofNullable(domainEffects.get(id));}
    public synchronized java.util.List<DecisionRequest> pendingDomainEffects(String domain){return decisions.values().stream().filter(q->q.status()==DecisionStatus.RESOLVED&&domain.equals(domainContext(q.decisionId()).get("domain"))&&!domainEffects.containsKey(q.decisionId())).limit(16).toList();}
    public synchronized java.util.List<DecisionRequest> pendingDomainEffects(String domain,java.util.Set<UUID> onlineRecipients){
        return decisions.values().stream().filter(q->onlineRecipients.contains(q.recipientPlayerId())&&q.status()==DecisionStatus.RESOLVED&&domain.equals(domainContext(q.decisionId()).get("domain"))&&!domainEffects.containsKey(q.decisionId())).limit(16).toList();
    }
    public synchronized boolean beginDomainEffect(UUID id,UUID viewer,UUID submission){
        var q=decisions.get(id);if(q==null||!q.recipientPlayerId().equals(viewer))throw new SecurityException("DECISION_RECIPIENT");
        var answer=acceptedAnswer(id).orElseThrow();if(!answer.submissionId().equals(submission)||domainContext(id).isEmpty())throw new IllegalArgumentException("DECISION_EFFECT_CONTEXT");
        if(domainEffects.containsKey(id))return false;
        domainEffects.put(id,new DomainEffect(submission,"APPLYING","",0));
        return persist(q,answer);
    }
    public synchronized void finishDomainEffect(UUID id,UUID submission,String state,String error,long revision){
        var effect=domainEffects.get(id);if(effect==null||!effect.submissionId().equals(submission)||!effect.state().equals("APPLYING"))throw new IllegalStateException("DECISION_EFFECT_NOT_APPLYING");
        if(!java.util.Set.of("APPLIED","FAILED").contains(state)||error==null||!error.matches("[A-Z0-9_]{0,80}")||revision<0)throw new IllegalArgumentException("DECISION_EFFECT_RESULT");
        domainEffects.put(id,new DomainEffect(submission,state,error,revision));
        boolean stored;
        try{stored=persist(decisions.get(id),acceptedAnswer(id).orElseThrow());}
        catch(RuntimeException failure){domainEffects.put(id,effect);throw failure;}
        if(!stored)throw new IllegalStateException("DECISION_EFFECT_CAS");
    }

    public synchronized Optional<DecisionRequest> get(UUID decisionId) {
        return Optional.ofNullable(decisions.get(decisionId));
    }
    public synchronized Optional<TaskLink> taskLink(UUID decisionId){return Optional.ofNullable(taskLinks.get(decisionId));}
    public synchronized void reconcileTasks(dev.mineagent.runtime.core.task.TaskManager tasks){
        for(var entry:new java.util.ArrayList<>(taskLinks.entrySet())){
            var q=decisions.get(entry.getKey());if(q==null||!(q.status()==DecisionStatus.OPEN||q.status()==DecisionStatus.DEFERRED))continue;
            var t=tasks.get(entry.getValue().taskId()).orElse(null);DecisionStatus target=null;
            if(t==null||java.util.Set.of(dev.mineagent.runtime.api.task.TaskStatus.CANCELLED,dev.mineagent.runtime.api.task.TaskStatus.COMPLETED,dev.mineagent.runtime.api.task.TaskStatus.FAILED).contains(t.status()))target=DecisionStatus.CANCELLED;
            else if(t.intentRevision()!=entry.getValue().intentRevision())target=DecisionStatus.SUPERSEDED;
            if(target!=null)transition(q.decisionId(),q.recipientPlayerId(),q.revision(),target,true);
        }
    }
    public synchronized DecisionTransitionResult cancelTask(UUID viewer,dev.mineagent.runtime.core.task.TaskManager tasks,UUID id,long expected)throws Exception{
        var q=decisions.get(id);if(q==null)return DecisionTransitionResult.rejected(unknownTransition(id,expected),"NOT_FOUND");
        if(!q.recipientPlayerId().equals(viewer))return DecisionTransitionResult.rejected(q,"FORBIDDEN");
        if(q.revision()!=expected)return DecisionTransitionResult.rejected(q,"STALE_REVISION");
        var link=taskLinks.get(id);if(link==null)return DecisionTransitionResult.rejected(q,"TASK_CONTEXT_REQUIRED");
        var t=tasks.get(link.taskId()).orElse(null);if(t==null||!t.ownerPlayerId().equals(viewer)||t.intentRevision()!=link.intentRevision())return DecisionTransitionResult.rejected(q,"STALE_TASK_REVISION");
        var changed=tasks.transition(t.taskId(),t.revision(),true,dev.mineagent.runtime.api.task.TaskStatus.CANCELLED);
        if(!changed.accepted())return DecisionTransitionResult.rejected(q,changed.errorCode());reconcileTasks(tasks);return DecisionTransitionResult.accepted(decisions.get(id));
    }

    public synchronized DecisionSubmitResult submit(
            UUID submittingPlayerId,
            long currentTaskRevision,
            DecisionAnswerSubmission submission
    ) {
        return submitValidated(submittingPlayerId, currentTaskRevision, submission, false);
    }

    private DecisionSubmitResult submitValidated(UUID submittingPlayerId, long currentTaskRevision,
                                                 DecisionAnswerSubmission submission, boolean taskContextVerified) {
        DecisionRequest request = decisions.get(submission.decisionId());
        if (request == null) {
            return DecisionSubmitResult.rejected(unknownRequest(submission), "NOT_FOUND");
        }

        if (!request.recipientPlayerId().equals(submittingPlayerId)) {
            return DecisionSubmitResult.rejected(request, "FORBIDDEN");
        }
        if (taskLinks.containsKey(submission.decisionId()) && !taskContextVerified)
            return DecisionSubmitResult.rejected(request, "TASK_CONTEXT_REQUIRED");
        AcceptedSubmission previous = submissions.get(submission.submissionId());
        if (previous != null) {
            if (previous.submission().equals(submission)) {
                return DecisionSubmitResult.accepted(previous.resolvedRequest(), true);
            }
            return DecisionSubmitResult.rejected(request, "SUBMISSION_ID_REUSED");
        }

        if (!request.recipientPlayerId().equals(submittingPlayerId)) {
            return DecisionSubmitResult.rejected(request, "FORBIDDEN");
        }
        if (request.taskRevision() != currentTaskRevision) {
            return DecisionSubmitResult.rejected(request, "STALE_TASK_REVISION");
        }
        if (request.revision() != submission.expectedRevision()) {
            return DecisionSubmitResult.rejected(request, "STALE_REVISION");
        }
        if (request.status() != DecisionStatus.OPEN) {
            return DecisionSubmitResult.rejected(request, "ALREADY_RESOLVED");
        }

        String validationError = validate(request, submission);
        if (validationError != null) {
            return DecisionSubmitResult.rejected(request, validationError);
        }

        DecisionRequest resolved = request.withStatus(DecisionStatus.RESOLVED);
        if (!persist(resolved, submission)) return DecisionSubmitResult.rejected(decisions.get(request.decisionId()), "PERSISTENCE_CONFLICT");
        decisions.put(resolved.decisionId(), resolved);
        submissions.put(submission.submissionId(), new AcceptedSubmission(submission, resolved));
        return DecisionSubmitResult.accepted(resolved, false);
    }

    public synchronized DecisionTransitionResult transition(
            UUID decisionId,
            UUID requestingPlayerId,
            long expectedRevision,
            DecisionStatus target,
            boolean operator
    ) {
        DecisionRequest request = decisions.get(decisionId);
        if (request == null) {
            return DecisionTransitionResult.rejected(unknownTransition(decisionId, expectedRevision), "NOT_FOUND");
        }
        if (!operator && !request.recipientPlayerId().equals(requestingPlayerId)) {
            return DecisionTransitionResult.rejected(request, "FORBIDDEN");
        }
        if (request.revision() != expectedRevision) {
            return DecisionTransitionResult.rejected(request, "STALE_REVISION");
        }
        if (!isAllowedTransition(request.status(), target, operator)) {
            return DecisionTransitionResult.rejected(request, "INVALID_TRANSITION");
        }
        DecisionRequest changed = request.withStatus(target);
        if (!persist(changed, null)) return DecisionTransitionResult.rejected(decisions.get(decisionId), "PERSISTENCE_CONFLICT");
        decisions.put(decisionId, changed);
        return DecisionTransitionResult.accepted(changed);
    }

    public synchronized java.util.List<DecisionRequest> pendingFor(UUID playerId) {
        return decisions.values().stream()
                .filter(request -> request.recipientPlayerId().equals(playerId))
                .filter(request -> request.status() == DecisionStatus.OPEN
                        || request.status() == DecisionStatus.DEFERRED)
                .sorted(java.util.Comparator.comparingLong(DecisionRequest::revision))
                .toList();
    }

    public synchronized java.util.List<DecisionRequest> allFor(UUID playerId) {
        return decisions.values().stream().filter(r -> r.recipientPlayerId().equals(playerId)).toList();
    }
    public record DecisionPage(java.util.List<DecisionRequest> requests, int page, int pages, int count, int pendingCount) {
        public DecisionPage { requests=java.util.List.copyOf(requests); }
    }
    /** Read-only, size-bounded history; no records or accepted submissions are evicted. */
    public synchronized DecisionPage pageFor(UUID viewer,int requestedPage) {
        if(requestedPage<0||requestedPage>100000)throw new IllegalArgumentException("DECISION_PAGE");
        var ordered=allFor(viewer).stream().sorted(java.util.Comparator
                .comparingInt((DecisionRequest q)->q.status()==DecisionStatus.OPEN||q.status()==DecisionStatus.DEFERRED?0:1)
                .thenComparing(DecisionRequest::decisionId)).toList();
        var pages=new java.util.ArrayList<java.util.List<DecisionRequest>>();
        var current=new java.util.ArrayList<DecisionRequest>();int size=2,pending=0;
        for(var q:ordered){
            if(q.status()==DecisionStatus.OPEN||q.status()==DecisionStatus.DEFERRED)pending++;
            final int length;try{length=json.writeValueAsString(q).length();}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("DECISION_SERIALIZATION",e);}
            if(length+2>24000)throw new IllegalStateException("DECISION_CARD_BUDGET");
            if(!current.isEmpty()&&(current.size()==16||size+length+1>16000)){pages.add(java.util.List.copyOf(current));current.clear();size=2;}
            size+=length+(current.isEmpty()?0:1);current.add(q);
        }
        if(!current.isEmpty()||pages.isEmpty())pages.add(java.util.List.copyOf(current));
        int page=Math.min(requestedPage,pages.size()-1);
        return new DecisionPage(pages.get(page),page,pages.size(),ordered.size(),pending);
    }
    public synchronized Optional<DecisionAnswerSubmission> acceptedAnswer(UUID decisionId) {
        return submissions.values().stream().filter(s -> s.resolvedRequest().decisionId().equals(decisionId))
                .map(AcceptedSubmission::submission).findFirst();
    }

    public synchronized DecisionRequest openForTask(DecisionRequest request, dev.mineagent.runtime.core.task.TaskManager tasks,
                                                     UUID taskId, java.util.Set<String> blockedNodes) throws Exception {
        if (request.status() != DecisionStatus.OPEN || decisions.containsKey(request.decisionId()))
            throw new IllegalArgumentException("DECISION_ALREADY_EXISTS_OR_NOT_OPEN");
        var task = tasks.get(taskId).orElseThrow();
        if (!task.ownerPlayerId().equals(request.recipientPlayerId()) || task.revision() != request.taskRevision())
            throw new SecurityException("DECISION_TASK_BINDING");
        var waiting = tasks.decisionNodes(taskId, task.revision(), blockedNodes, true, "等待决定 " + request.decisionId());
        if (!waiting.accepted()) throw new IllegalStateException(waiting.errorCode());
        var bound = new DecisionRequest(request.decisionId(), request.revision(), request.recipientPlayerId(), waiting.task().intentRevision(),
                request.kind(), request.title(), request.question(), request.options(), request.selectionMode(), request.minSelections(),
                request.maxSelections(), request.allowCustomInput(), request.status());
        taskLinks.put(bound.decisionId(), new TaskLink(taskId, waiting.task().revision(), blockedNodes, "WAITING", waiting.task().intentRevision()));
        try { open(bound); }
        catch (RuntimeException failure) {
            tasks.transition(taskId, waiting.task().revision(), true, dev.mineagent.runtime.api.task.TaskStatus.PAUSED);
            throw failure;
        }
        return bound;
    }

    public synchronized DecisionSubmitResult submitForTask(UUID player, dev.mineagent.runtime.core.task.TaskManager tasks,
                                                           DecisionAnswerSubmission answer) {
        var request = decisions.get(answer.decisionId());
        if (request == null) return submit(player, 0, answer);
        TaskLink link = taskLinks.get(answer.decisionId());
        long revision = link == null ? request.taskRevision() : tasks.get(link.taskId())
                .filter(t -> t.status() == dev.mineagent.runtime.api.task.TaskStatus.RUNNING && t.intentRevision() == link.intentRevision())
                .map(t -> request.taskRevision()).orElse(-1L);
        return submitValidated(player, revision, answer, true);
    }

    /** Durable accepted-answer outbox. Resumes metadata only; never replays arbitrary native game mutations. */
    public synchronized void resumeAcceptedTasks(dev.mineagent.runtime.core.task.TaskManager tasks) throws Exception {
        int budget = 8;
        for (var entry : new java.util.ArrayList<>(taskLinks.entrySet())) {
            TaskLink link = entry.getValue();
            if (!link.state().equals("WAITING")) continue;
            var answer = acceptedAnswer(entry.getKey()).orElse(null);
            if (answer == null) continue;
            if (budget-- == 0) break;
            var task = tasks.get(link.taskId()).orElse(null);
            String marker = "决定 " + entry.getKey() + ": ";
            String state;
            if (task == null) state = "TASK_MISSING";
            else if (task.lastChangeReason().startsWith(marker)) state = "APPLIED";
            else if (task.intentRevision() != link.intentRevision()) state = "STALE_TASK";
            else {
                var question = decisions.get(entry.getKey());
                var selected = question.options().stream().filter(o -> answer.selectedOptionIds().contains(o.optionId()))
                        .map(o -> o.optionId() + "=" + o.title()).toList();
                var result = tasks.decisionNodes(task.taskId(), task.revision(), link.nodeIds(), false,
                        marker + "问题: " + question.question() + "; 选择: " + selected + "; 玩家补充: " + answer.customText() + "; 来源: " + answer.source());
                state = result.accepted() ? "APPLIED" : result.errorCode();
            }
            taskLinks.put(entry.getKey(), new TaskLink(link.taskId(), link.taskRevision(), link.nodeIds(), state, link.intentRevision()));
            persist(decisions.get(entry.getKey()), answer);
        }
    }

    @Override public synchronized void close() throws Exception { if (repository != null) repository.close(); }

    private static boolean isAllowedTransition(DecisionStatus current, DecisionStatus target, boolean operator) {
        if (current == DecisionStatus.OPEN) {
            if (target == DecisionStatus.DEFERRED) {
                return true;
            }
            if (target == DecisionStatus.CANCELLED) {
                return true;
            }
            return operator && (target == DecisionStatus.EXPIRED || target == DecisionStatus.SUPERSEDED);
        }
        if (current == DecisionStatus.DEFERRED) {
            if (target == DecisionStatus.OPEN || target == DecisionStatus.CANCELLED) {
                return true;
            }
            return operator && (target == DecisionStatus.EXPIRED || target == DecisionStatus.SUPERSEDED);
        }
        return false;
    }

    private static String validate(DecisionRequest request, DecisionAnswerSubmission submission) {
        if(request.kind()==dev.mineagent.runtime.api.decision.DecisionKind.AUTHORIZATION&&submission.source()!=dev.mineagent.runtime.api.decision.AnswerSource.UI)return "AUTHORIZATION_REQUIRES_UI";
        var selected = new HashSet<>(submission.selectedOptionIds());
        if (selected.size() != submission.selectedOptionIds().size()) {
            return "DUPLICATE_OPTION";
        }
        var allowed = request.options().stream().map(option -> option.optionId()).collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(selected)) {
            return "UNKNOWN_OPTION";
        }
        if (request.selectionMode() == SelectionMode.SINGLE && selected.size() > 1) {
            return "TOO_MANY_SELECTIONS";
        }
        if (selected.size() > request.maxSelections()) {
            return "TOO_MANY_SELECTIONS";
        }
        boolean hasCustomText = !submission.customText().isBlank();
        if(request.kind()==dev.mineagent.runtime.api.decision.DecisionKind.AUTHORIZATION&&(selected.isEmpty()||selected.size()<request.minSelections()))return "AUTHORIZATION_CHOICE_REQUIRED";
        if (!hasCustomText && selected.size() < request.minSelections()) {
            return "TOO_FEW_SELECTIONS";
        }
        if (hasCustomText && !request.allowCustomInput()) {
            return "CUSTOM_INPUT_NOT_ALLOWED";
        }
        if (!hasCustomText && selected.isEmpty()) {
            return "EMPTY_ANSWER";
        }
        return null;
    }

    private static DecisionRequest unknownRequest(DecisionAnswerSubmission submission) {
        return new DecisionRequest(submission.decisionId(), submission.expectedRevision(), new UUID(0, 0), 0,
                dev.mineagent.runtime.api.decision.DecisionKind.CLARIFICATION, "", "",
                java.util.List.of(), SelectionMode.MULTIPLE, 0, 0, true, DecisionStatus.CANCELLED);
    }

    private static DecisionRequest unknownTransition(UUID decisionId, long revision) {
        return new DecisionRequest(decisionId, Math.max(0, revision), new UUID(0, 0), 0,
                dev.mineagent.runtime.api.decision.DecisionKind.CLARIFICATION, "未知选择", "未知选择",
                java.util.List.of(), SelectionMode.MULTIPLE, 0, 0, true, DecisionStatus.CANCELLED);
    }

    private record AcceptedSubmission(DecisionAnswerSubmission submission, DecisionRequest resolvedRequest) {
    }
}
