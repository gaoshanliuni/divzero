package dev.mineagent.runtime.agent.model;

import dev.mineagent.runtime.api.model.ModelCapability;
import dev.mineagent.runtime.api.model.ModelProvider;
import dev.mineagent.runtime.api.model.ModelRequest;
import dev.mineagent.runtime.api.model.ModelResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

public final class ResilientModelProvider implements ModelProvider {
    private final ModelProvider delegate;
    private final int retries;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private int consecutiveFailures;
    private long circuitOpenedAt = Long.MIN_VALUE;

    public ResilientModelProvider(
            ModelProvider delegate,
            int retries,
            int failureThreshold,
            Duration cooldown,
            Clock clock
    ) {
        if (delegate == null || retries < 0 || retries > 5 || failureThreshold < 1
                || cooldown == null || cooldown.isNegative() || cooldown.isZero() || clock == null) {
            throw new IllegalArgumentException("invalid resilient provider configuration");
        }
        this.delegate = delegate;
        this.retries = retries;
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public synchronized ModelResponse complete(ModelRequest request) {
        if (circuitOpenedAt != Long.MIN_VALUE) {
            if (clock.millis() - circuitOpenedAt < cooldown.toMillis()) {
                throw new ModelCircuitOpenException(id());
            }
            circuitOpenedAt = Long.MIN_VALUE;
            consecutiveFailures = 0;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                ModelResponse response = delegate.complete(request);
                consecutiveFailures = 0;
                return response;
            } catch (RuntimeException failure) {
                last = failure;
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    circuitOpenedAt = clock.millis();
                    break;
                }
            }
        }
        throw last == null ? new IllegalStateException("model request failed") : last;
    }
}
