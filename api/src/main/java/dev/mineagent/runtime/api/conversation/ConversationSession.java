package dev.mineagent.runtime.api.conversation;

import java.util.Objects;
import java.util.UUID;

public record ConversationSession(
        UUID conversationId,
        UUID playerId,
        UUID agentId,
        String title,
        long revision,
        boolean archived,
        long updatedAtEpochMillis
) {
    public ConversationSession {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(title, "title");
        if (title.isBlank() || title.codePointCount(0, title.length()) > 128 || revision < 0) {
            throw new IllegalArgumentException("invalid conversation session");
        }
    }

    public ConversationSession touch(long timestamp) {
        return new ConversationSession(conversationId, playerId, agentId, title, revision + 1, archived, timestamp);
    }

    public ConversationSession withArchived(boolean value, long timestamp) {
        return new ConversationSession(conversationId, playerId, agentId, title, revision + 1, value, timestamp);
    }
}
