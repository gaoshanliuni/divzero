package dev.mineagent.runtime.agent.body;

import dev.mineagent.runtime.api.agent.BodyDomain;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BehaviorArbiter {
    private final Map<BodyDomain, Lease> leases = new EnumMap<>(BodyDomain.class);

    public synchronized AcquireResult acquire(UUID taskId, Set<BodyDomain> domains, int priority) {
        if (domains.isEmpty()) {
            throw new IllegalArgumentException("domains must not be empty");
        }
        var preempted = new LinkedHashSet<UUID>();
        for (BodyDomain domain : domains) {
            Lease current = leases.get(domain);
            if (current != null && !current.taskId().equals(taskId)) {
                if (current.priority() >= priority) {
                    return new AcquireResult(false, Set.of());
                }
                preempted.add(current.taskId());
            }
        }
        preempted.forEach(this::release);
        domains.forEach(domain -> leases.put(domain, new Lease(taskId, priority)));
        return new AcquireResult(true, preempted);
    }

    public synchronized void release(UUID taskId) {
        leases.entrySet().removeIf(entry -> entry.getValue().taskId().equals(taskId));
    }

    public synchronized Optional<UUID> ownerOf(BodyDomain domain) {
        return Optional.ofNullable(leases.get(domain)).map(Lease::taskId);
    }

    private record Lease(UUID taskId, int priority) {
    }
}
