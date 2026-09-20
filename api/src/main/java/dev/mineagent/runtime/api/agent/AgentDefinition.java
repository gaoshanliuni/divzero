package dev.mineagent.runtime.api.agent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AgentDefinition(
        UUID agentId,
        String displayName,
        String profileName,
        UUID ownerPlayerId,
        AgentMode mode,
        Set<UUID> collaboratorPlayerIds
) {
    public AgentDefinition {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(mode, "mode");
        collaboratorPlayerIds = Set.copyOf(collaboratorPlayerIds);
    }

    public AgentDefinition withCollaborators(Set<UUID> collaborators) {
        return new AgentDefinition(agentId, displayName, profileName, ownerPlayerId, mode, collaborators);
    }

    public AgentDefinition withDisplayName(String name) {
        return new AgentDefinition(agentId, name, profileName, ownerPlayerId, mode, collaboratorPlayerIds);
    }

    public AgentDefinition withMode(AgentMode newMode) {
        return new AgentDefinition(agentId, displayName, profileName, ownerPlayerId, newMode, collaboratorPlayerIds);
    }
}
