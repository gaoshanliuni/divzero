package dev.mineagent.runtime.core.conversation;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Snapshot of a connection identity. Same player UUID after reconnect is not the old request recipient. */
public final class ConversationCallbackFence {
    private final Object recipient;
    private final BooleanSupplier contextActive;
    private final Supplier<?> currentRecipient;
    public ConversationCallbackFence(Object recipient,BooleanSupplier contextActive,Supplier<?> currentRecipient){
        this.recipient=Objects.requireNonNull(recipient);this.contextActive=Objects.requireNonNull(contextActive);this.currentRecipient=Objects.requireNonNull(currentRecipient);
    }
    public boolean permits(){return contextActive.getAsBoolean()&&currentRecipient.get()==recipient;}
}
