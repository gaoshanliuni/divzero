package dev.mineagent.runtime.core.agent;

public record AgentMutationResult(boolean accepted, String errorCode, PersistentAgent agent) {
    public AgentMutationResult {
        errorCode = errorCode == null ? "" : errorCode;
    }

    public static AgentMutationResult accepted(PersistentAgent agent) {
        return new AgentMutationResult(true, "", agent);
    }

    public static AgentMutationResult rejected(PersistentAgent agent, String code) {
        return new AgentMutationResult(false, code, agent);
    }
}
