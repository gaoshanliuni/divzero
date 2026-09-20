package dev.mineagent.runtime.client.conversation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class StreamingConversationBuffer {
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private final Map<UUID, State> streams = new HashMap<>();

    public synchronized void begin(UUID conversationId) {
        streams.put(Objects.requireNonNull(conversationId, "conversationId"), new State());
    }

    public synchronized boolean append(UUID conversationId, int sequence, String delta) {
        State state = streams.get(Objects.requireNonNull(conversationId, "conversationId"));
        if (state == null || sequence < 0 || delta == null) {
            return false;
        }
        if (sequence < state.nextSequence) {
            return true;
        }
        if (sequence != state.nextSequence || state.text.length() + delta.length() > MAX_TEXT_LENGTH) {
            return false;
        }
        state.text.append(delta);
        state.nextSequence++;
        return true;
    }

    public synchronized String text(UUID conversationId) {
        State state = streams.get(conversationId);
        return state == null ? "" : state.text.toString();
    }

    public synchronized boolean streaming(UUID conversationId) {
        return streams.containsKey(conversationId);
    }

    public synchronized String complete(UUID conversationId) {
        State state = streams.remove(conversationId);
        return state == null ? "" : state.text.toString();
    }

    private static final class State {
        private final StringBuilder text = new StringBuilder();
        private int nextSequence;
    }
}
