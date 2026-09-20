package dev.mineagent.runtime.api.interaction;

import java.util.Objects;
import java.util.UUID;

public record InteractionInput(
        UUID playerId,
        InteractionSource source,
        String text,
        UUID currentConversationAgentId
) {
    public InteractionInput {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("interaction text must not be blank");
        }
    }
}
