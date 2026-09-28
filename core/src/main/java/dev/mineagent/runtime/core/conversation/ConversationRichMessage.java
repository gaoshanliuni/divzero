package dev.mineagent.runtime.core.conversation;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Structured message actions are data, never inferred from prose or executed as commands. */
public record ConversationRichMessage(String text, String color, List<Option> buttons) {
    public record Option(String label, String action, String value) {
        public Option {
            check(label, 80); check(value, 2048);if("preview".equals(action))UUID.fromString(value);
            if (action == null || !Set.of("confirm", "suggest", "copy", "preview").contains(action))
                throw new IllegalArgumentException("CONVERSATION_BUTTON_ACTION");
        }
    }
    public record Saved(UUID messageId, ConversationRichMessage definition, long expiresAt,
                        int selected, UUID selectionOperation, String state, long revision) {}
    public record Claim(Saved message, boolean dispatch) {}

    public ConversationRichMessage {
        check(text, 2048);
        if (color == null || !color.matches("#[a-fA-F0-9]{6}"))
            throw new IllegalArgumentException("CONVERSATION_BUTTON_COLOR");
        buttons = List.copyOf(buttons);
        if (buttons.size() > 8) throw new IllegalArgumentException("CONVERSATION_BUTTON_LIMIT");
    }
    private static void check(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("CONVERSATION_BUTTON_TEXT");
    }
}
