package dev.mineagent.runtime.core.interaction;

import java.util.Optional;
import java.util.UUID;

public record AddressResolution(Optional<UUID> agentId, AddressResolutionKind kind) {
    public AddressResolution {
        agentId = agentId == null ? Optional.empty() : agentId;
    }
}
