package dev.mineagent.runtime.core.permission;

import dev.mineagent.runtime.api.permission.PermissionAction;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PermissionService {
    private static final Set<PermissionAction> REGULAR_ACTIONS =
            Set.of(PermissionAction.CHAT, PermissionAction.START_TASK);

    private final Map<UUID, Set<PermissionAction>> trustedActions = new LinkedHashMap<>();
    private final Map<UUID, Map<PermissionAction,Long>> actionRevisions = new LinkedHashMap<>();
    private final Map<UUID, Ownership> ownership = new LinkedHashMap<>();

    public synchronized boolean allowed(UUID playerId, boolean operator, PermissionAction action) {
        if (playerId == null || action == null) {
            return false;
        }
        return operator
                || REGULAR_ACTIONS.contains(action)
                || trustedActions.getOrDefault(playerId, Set.of()).contains(action);
    }

    public synchronized void setTrustedActions(UUID playerId, Set<PermissionAction> actions) {
        if (playerId == null || actions == null) {
            throw new IllegalArgumentException("player and actions are required");
        }
        var next=actions.isEmpty()?Set.<PermissionAction>of():Set.copyOf(EnumSet.copyOf(actions));
        var previous=trustedActions.getOrDefault(playerId,Set.of());
        for(var action:PermissionAction.values())if(previous.contains(action)!=next.contains(action)){
            var revisions=actionRevisions.computeIfAbsent(playerId,ignored->new java.util.EnumMap<>(PermissionAction.class));
            revisions.put(action,Math.addExact(revisions.getOrDefault(action,0L),1));
        }
        trustedActions.put(playerId,next);
    }

    public synchronized long actionRevision(UUID playerId,PermissionAction action){return actionRevisions.getOrDefault(playerId,Map.of()).getOrDefault(action,0L);}

    public synchronized Set<PermissionAction> trustedActions(UUID playerId) {
        return trustedActions.getOrDefault(playerId, Set.of());
    }

    public synchronized Map<UUID, Set<PermissionAction>> allTrustedActions() {
        var copy = new LinkedHashMap<UUID, Set<PermissionAction>>();
        trustedActions.forEach((player, actions) -> copy.put(player, Set.copyOf(actions)));
        return Map.copyOf(copy);
    }

    public synchronized void registerOwnership(UUID agentId, UUID ownerId) {
        if (agentId == null || ownerId == null) {
            throw new IllegalArgumentException("agent and owner are required");
        }
        if (ownership.putIfAbsent(agentId, new Ownership(ownerId, Set.of())) != null) {
            throw new IllegalArgumentException("agent ownership already exists");
        }
    }

    public synchronized Optional<UUID> owner(UUID agentId) {
        Ownership value = ownership.get(agentId);
        return value == null ? Optional.empty() : Optional.of(value.ownerId());
    }

    public synchronized Set<UUID> collaborators(UUID agentId) {
        Ownership value = ownership.get(agentId);
        return value == null ? Set.of() : value.collaborators();
    }

    public synchronized void setCollaborators(UUID agentId, UUID requestingPlayerId, Set<UUID> collaborators) {
        Ownership value = ownership.get(agentId);
        if (value == null) {
            throw new IllegalArgumentException("unknown agent");
        }
        if (!value.ownerId().equals(requestingPlayerId)) {
            throw new SecurityException("only the owner may change collaborators");
        }
        if (collaborators == null || collaborators.contains(value.ownerId())) {
            throw new IllegalArgumentException("invalid collaborators");
        }
        ownership.put(agentId, new Ownership(value.ownerId(), Set.copyOf(collaborators)));
    }

    public synchronized boolean canMutateAgent(UUID agentId, UUID playerId, boolean operator) {
        if (operator) {
            return true;
        }
        Ownership value = ownership.get(agentId);
        return value != null && (value.ownerId().equals(playerId) || value.collaborators().contains(playerId));
    }
    /** Use the authoritative persisted definition on server paths; the legacy UUID cache may be empty after restart. */
    public boolean canMutateAgent(dev.mineagent.runtime.api.agent.AgentDefinition definition,UUID playerId,boolean operator){
        return definition!=null&&playerId!=null&&(operator||definition.ownerPlayerId().equals(playerId)||definition.collaboratorPlayerIds().contains(playerId));
    }

    public synchronized void removeOwnership(UUID agentId) {
        ownership.remove(agentId);
    }

    private record Ownership(UUID ownerId, Set<UUID> collaborators) {
    }
}
