package dev.mineagent.runtime.core.agent;

public final class AgentLimitException extends RuntimeException {
    public AgentLimitException(int limit) {
        super("AI 玩家数量已达到上限 " + limit);
    }
}
