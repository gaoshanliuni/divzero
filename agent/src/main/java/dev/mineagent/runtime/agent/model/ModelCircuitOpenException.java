package dev.mineagent.runtime.agent.model;

public final class ModelCircuitOpenException extends RuntimeException {
    public ModelCircuitOpenException(String providerId) {
        super("model provider circuit is open: " + providerId);
    }
}
