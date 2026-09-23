package dev.mineagent.runtime.neoforge.ui;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Bounded plain-text command feedback; preserves the original player's output/admin policy. */
final class ConversationCommandFeedback implements CommandSource {
    private final CommandSource delegate;
    private final List<String> messages = new ArrayList<>();
    private int characters;
    private boolean truncated;

    ConversationCommandFeedback(CommandSource delegate) { this.delegate = delegate; }
    @Override public void sendSystemMessage(Component message) {
        String text = message.getString();
        messages.add(text);
        delegate.sendSystemMessage(message);
    }
    @Override public boolean acceptsSuccess() { return delegate.acceptsSuccess(); }
    @Override public boolean acceptsFailure() { return delegate.acceptsFailure(); }
    @Override public boolean shouldInformAdmins() { return delegate.shouldInformAdmins(); }
    @Override public boolean alwaysAccepts() { return delegate.alwaysAccepts(); }
    List<String> messages() { return List.copyOf(messages); }
    boolean truncated() { return truncated; }
}
