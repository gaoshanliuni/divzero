package dev.mineagent.runtime.api.conversation;

import java.util.Objects;
import java.util.UUID;

public record ConversationMessage(
        UUID messageId,
        UUID conversationId,
        UUID authorId,
        MessageRole role,
        String text,
        ConversationVisibility visibility,
        long createdAtEpochMillis
) {
    public ConversationMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(visibility, "visibility");
        text = text.strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 32_768) {
            throw new IllegalArgumentException("invalid conversation message");
        }
    }
}
