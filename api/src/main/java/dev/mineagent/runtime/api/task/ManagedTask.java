package dev.mineagent.runtime.api.task;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ManagedTask(
        UUID taskId,
        UUID worldId,
        UUID agentId,
        UUID ownerPlayerId,
        String title,
        int priority,
        long revision,
        TaskStatus status,
        List<TaskStep> steps,
        String lastChangeReason,
        long updatedAtEpochMillis,
        long intentRevision
) {
    public ManagedTask(UUID taskId, UUID worldId, UUID agentId, UUID ownerPlayerId, String title, int priority,
                       long revision, TaskStatus status, List<TaskStep> steps, String lastChangeReason, long updatedAtEpochMillis) {
        this(taskId, worldId, agentId, ownerPlayerId, title, priority, revision, status, steps, lastChangeReason, updatedAtEpochMillis, revision);
    }
    public ManagedTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
        lastChangeReason = lastChangeReason == null ? "" : lastChangeReason;
        // Older records had only the mutation revision; use that as their initial intent baseline.
        if (intentRevision < 1) intentRevision = Math.max(1, revision);
    }

    public List<String> runnableStepIds() {
        var completed = steps.stream()
                .filter(step -> step.status() == TaskNodeStatus.COMPLETED)
                .map(TaskStep::stepId)
                .collect(java.util.stream.Collectors.toSet());
        return steps.stream()
                .filter(step -> step.status() == TaskNodeStatus.PENDING)
                .filter(step -> completed.containsAll(step.dependencies()))
                .map(TaskStep::stepId)
                .toList();
    }
}
