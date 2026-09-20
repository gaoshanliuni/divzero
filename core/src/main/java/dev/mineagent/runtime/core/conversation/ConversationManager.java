package dev.mineagent.runtime.core.conversation;

import dev.mineagent.runtime.api.conversation.ConversationMessage;
import dev.mineagent.runtime.api.conversation.ConversationSession;
import dev.mineagent.runtime.api.conversation.ConversationVisibility;
import dev.mineagent.runtime.api.conversation.MessageRole;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ConversationManager {
    private static final UUID SYSTEM_AUTHOR = new UUID(0, 0);
    private final Clock clock;
    private final Map<UUID, ConversationSession> sessions = new LinkedHashMap<>();
    private final Map<ConversationKey, UUID> activeByParticipants = new LinkedHashMap<>();
    private final Map<UUID, List<ConversationMessage>> messages = new LinkedHashMap<>();
    private final Map<DraftKey, String> drafts = new LinkedHashMap<>();

    public ConversationManager() {
        this(Clock.systemUTC());
    }

    public ConversationManager(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public synchronized ConversationSession open(UUID playerId, UUID agentId, String title) {
        var key = new ConversationKey(required(playerId, "playerId"), required(agentId, "agentId"));
        UUID currentId = activeByParticipants.get(key);
        if (currentId != null) {
            ConversationSession current = sessions.get(currentId);
            if (current != null && !current.archived()) {
                return current;
            }
        }
        String normalizedTitle = title == null ? "" : title.strip();
        long now = clock.millis();
        var session = new ConversationSession(
                UUID.randomUUID(), key.playerId(), key.agentId(), normalizedTitle, 0, false, now);
        sessions.put(session.conversationId(), session);
        activeByParticipants.put(key, session.conversationId());
        messages.put(session.conversationId(), new ArrayList<>());
        return session;
    }

    public synchronized ConversationMessage append(
            UUID conversationId,
            UUID requestingPlayerId,
            MessageRole role,
            String text,
            ConversationVisibility visibility
    ) {
        ConversationSession session = requireSession(conversationId);
        if (session.archived()) {
            throw new IllegalStateException("conversation is archived");
        }
        if (!session.playerId().equals(requestingPlayerId)) {
            throw new SecurityException("player is not the conversation participant");
        }
        UUID authorId = switch (role) {
            case USER -> session.playerId();
            case ASSISTANT -> session.agentId();
            case SYSTEM, TOOL -> SYSTEM_AUTHOR;
        };
        var message = new ConversationMessage(
                UUID.randomUUID(), conversationId, authorId, role, text, visibility, clock.millis());
        messages.get(conversationId).add(message);
        sessions.put(conversationId, session.touch(clock.millis()));
        drafts.remove(new DraftKey(conversationId, requestingPlayerId));
        return message;
    }

    public synchronized List<ConversationMessage> history(UUID conversationId, UUID viewerId, boolean operator) {
        ConversationSession session = requireSession(conversationId);
        boolean participant = session.playerId().equals(viewerId);
        return messages.get(conversationId).stream()
                .filter(message -> message.visibility() == ConversationVisibility.PUBLIC || participant || operator)
                .toList();
    }

    public synchronized void saveDraft(UUID conversationId, UUID playerId, String text) {
        ConversationSession session = requireSession(conversationId);
        if (!session.playerId().equals(playerId)) {
            throw new SecurityException("player is not the conversation participant");
        }
        String value = text == null ? "" : text;
        if (value.codePointCount(0, value.length()) > 32_768) {
            throw new IllegalArgumentException("draft is too long");
        }
        DraftKey key = new DraftKey(conversationId, playerId);
        if (value.isBlank()) {
            drafts.remove(key);
        } else {
            drafts.put(key, value);
        }
    }

    public synchronized String draft(UUID conversationId, UUID playerId) {
        requireSession(conversationId);
        return drafts.getOrDefault(new DraftKey(conversationId, playerId), "");
    }

    public synchronized boolean archive(UUID conversationId, UUID playerId, boolean operator) {
        ConversationSession session = sessions.get(conversationId);
        if (session == null || (!operator && !session.playerId().equals(playerId))) {
            return false;
        }
        if (!session.archived()) {
            sessions.put(conversationId, session.withArchived(true, clock.millis()));
            activeByParticipants.remove(new ConversationKey(session.playerId(), session.agentId()));
        }
        return true;
    }

    public synchronized Optional<ConversationSession> session(UUID conversationId) {
        return Optional.ofNullable(sessions.get(conversationId));
    }

    public synchronized List<ConversationSession> sessionsFor(UUID playerId) {
        return sessions.values().stream()
                .filter(session -> session.playerId().equals(playerId))
                .sorted(java.util.Comparator.comparingLong(ConversationSession::updatedAtEpochMillis).reversed())
                .toList();
    }

    private ConversationSession requireSession(UUID conversationId) {
        ConversationSession session = sessions.get(conversationId);
        if (session == null) {
            throw new IllegalArgumentException("unknown conversation");
        }
        return session;
    }

    private static UUID required(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private record ConversationKey(UUID playerId, UUID agentId) {
    }

    private record DraftKey(UUID conversationId, UUID playerId) {
    }
}
