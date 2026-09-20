package dev.mineagent.runtime.integrations.ysm;

import dev.mineagent.runtime.api.decision.DecisionStatus;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-server bounded decision contexts with deterministic insertion-order eviction.
 */
public final class AppearanceDecisionRegistry<T> {
    private final int maximumEntries;
    private final LinkedHashMap<UUID, T> contexts = new LinkedHashMap<>();

    public AppearanceDecisionRegistry(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public synchronized Optional<Evicted<T>> put(UUID decisionId, T context) {
        contexts.put(Objects.requireNonNull(decisionId, "decisionId"),
                Objects.requireNonNull(context, "context"));
        if (contexts.size() <= maximumEntries) {
            return Optional.empty();
        }
        var iterator = contexts.entrySet().iterator();
        var evicted = iterator.next();
        iterator.remove();
        return Optional.of(new Evicted<>(evicted.getKey(), evicted.getValue()));
    }

    public synchronized Optional<T> get(UUID decisionId) {
        return Optional.ofNullable(contexts.get(Objects.requireNonNull(decisionId, "decisionId")));
    }

    public synchronized Optional<T> resolve(UUID decisionId) {
        return Optional.ofNullable(contexts.remove(Objects.requireNonNull(decisionId, "decisionId")));
    }

    public synchronized void transition(UUID decisionId, DecisionStatus status) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(status, "status");
        if (status == DecisionStatus.RESOLVED
                || status == DecisionStatus.CANCELLED
                || status == DecisionStatus.EXPIRED
                || status == DecisionStatus.SUPERSEDED) {
            contexts.remove(decisionId);
        }
    }

    public synchronized int size() {
        return contexts.size();
    }

    public record Evicted<T>(UUID decisionId, T context) {
        public Evicted {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(context, "context");
        }
    }
}
