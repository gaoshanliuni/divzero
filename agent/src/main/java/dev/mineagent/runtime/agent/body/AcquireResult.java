package dev.mineagent.runtime.agent.body;

import java.util.Set;
import java.util.UUID;

public record AcquireResult(boolean acquired, Set<UUID> preemptedTaskIds) {
    public AcquireResult {
        preemptedTaskIds = Set.copyOf(preemptedTaskIds);
    }
}
