package dev.mineagent.runtime.core.agent;

import dev.mineagent.runtime.api.agent.AgentDefinition;
import dev.mineagent.runtime.api.agent.AgentMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AgentRegistry {
    private int maximumAgents;
    private final Map<UUID, AgentDefinition> agents = new LinkedHashMap<>();

    public AgentRegistry(int maximumAgents) {
        if (maximumAgents < 1) {
            throw new IllegalArgumentException("maximumAgents must be positive");
        }
        this.maximumAgents = maximumAgents;
    }

    /** Zero is useful internally when transient players consume all remaining admission slots. */
    public synchronized void setMaximumAgents(int maximumAgents) {
        if (maximumAgents < 0) throw new IllegalArgumentException("maximumAgents must not be negative");
        this.maximumAgents = maximumAgents;
    }

    public synchronized void requireCreationCapacity() {
        if (agents.size() >= maximumAgents) throw new AgentLimitException(maximumAgents);
    }

    public synchronized AgentDefinition create(String displayName, UUID ownerPlayerId, AgentMode mode) {
        return createWithId(UUID.randomUUID(),displayName,ownerPlayerId,mode);
    }
    public synchronized AgentDefinition createWithId(UUID id,String displayName,UUID ownerPlayerId,AgentMode mode){
        java.util.Objects.requireNonNull(id);if(agents.containsKey(id))throw new IllegalArgumentException("AGENT_ID_EXISTS");
        String normalized = normalizeName(displayName);
        requireCreationCapacity();
        if (agents.values().stream().anyMatch(agent -> normalizeName(agent.displayName()).equals(normalized))) {
            throw new IllegalArgumentException("AI 玩家名称已存在");
        }
        String profileName = "MA_" + id.toString().replace("-", "").substring(0, 12);
        var definition = new AgentDefinition(id, displayName.strip(), profileName, ownerPlayerId, mode, java.util.Set.of());
        agents.put(id, definition);
        return definition;
    }

    public synchronized void restore(AgentDefinition definition) {
        java.util.Objects.requireNonNull(definition, "definition");
        String normalized = normalizeName(definition.displayName());
        // Restore is not new admission. Lowering the cap must not break login or discard saved AI.
        if (agents.containsKey(definition.agentId())
                || agents.values().stream().anyMatch(agent -> normalizeName(agent.displayName()).equals(normalized))) {
            throw new IllegalArgumentException("persisted AI player conflicts with active registry");
        }
        agents.put(definition.agentId(), definition);
    }

    public synchronized Optional<AgentDefinition> get(UUID agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    public synchronized List<AgentDefinition> all() {
        return List.copyOf(new ArrayList<>(agents.values()));
    }

    public synchronized boolean setCollaborator(
            UUID agentId,
            UUID actingPlayerId,
            UUID collaboratorPlayerId,
            boolean enabled
    ) {
        AgentDefinition current = agents.get(agentId);
        if (current == null || !current.ownerPlayerId().equals(actingPlayerId)) {
            return false;
        }
        var collaborators = new LinkedHashSet<>(current.collaboratorPlayerIds());
        if (enabled) {
            collaborators.add(collaboratorPlayerId);
        } else {
            collaborators.remove(collaboratorPlayerId);
        }
        agents.put(agentId, current.withCollaborators(collaborators));
        return true;
    }

    public synchronized boolean rename(
            UUID agentId,
            UUID actingPlayerId,
            boolean operator,
            String displayName
    ) {
        AgentDefinition current = agents.get(agentId);
        if (!canManage(current, actingPlayerId, operator)) {
            return false;
        }
        String normalized = normalizeName(displayName);
        if (agents.values().stream().anyMatch(agent -> !agent.agentId().equals(agentId)
                && normalizeName(agent.displayName()).equals(normalized))) {
            throw new IllegalArgumentException("AI 玩家名称已存在");
        }
        agents.put(agentId, current.withDisplayName(displayName.strip()));
        return true;
    }

    public synchronized boolean setMode(
            UUID agentId,
            UUID actingPlayerId,
            boolean operator,
            AgentMode mode
    ) {
        AgentDefinition current = agents.get(agentId);
        if (!canManage(current, actingPlayerId, operator)) {
            return false;
        }
        agents.put(agentId, current.withMode(java.util.Objects.requireNonNull(mode, "mode")));
        return true;
    }

    public synchronized boolean remove(UUID agentId, UUID actingPlayerId, boolean operator) {
        AgentDefinition current = agents.get(agentId);
        if (!canManage(current, actingPlayerId, operator)) {
            return false;
        }
        agents.remove(agentId);
        return true;
    }

    private static boolean canManage(AgentDefinition agent, UUID actingPlayerId, boolean operator) {
        return agent != null && (operator || agent.ownerPlayerId().equals(actingPlayerId));
    }

    private static String normalizeName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("AI 玩家名称不能为空");
        }
        String stripped = displayName.strip();
        if (stripped.codePointCount(0, stripped.length()) > 32) {
            throw new IllegalArgumentException("AI 玩家名称不能超过 32 个字符");
        }
        return stripped.toLowerCase(Locale.ROOT);
    }
}
