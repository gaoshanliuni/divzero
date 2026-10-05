package dev.mineagent.runtime.legacy189.bridge;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Java 8 value type shared by a game adapter and the Java 25 service process. */
public final class BridgeFrame {
    public enum Kind { HELLO, REQUEST, RESULT, EVENT, CANCEL, GOODBYE }
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private final Kind kind;
    private final UUID operation;
    private final UUID world;
    private final UUID owner;
    private final UUID session;
    private final long revision;
    private final byte[] payload;

    public BridgeFrame(Kind kind, UUID operation, UUID world, UUID owner,
                       UUID session, long revision, byte[] payload) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.world = Objects.requireNonNull(world, "world");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.session = Objects.requireNonNull(session, "session");
        Objects.requireNonNull(payload, "payload");
        if (revision < 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("BRIDGE_FRAME_BOUNDS");
        }
        this.revision = revision;
        this.payload = payload.clone();
    }

    public Kind kind() { return kind; }
    public UUID operation() { return operation; }
    public UUID world() { return world; }
    public UUID owner() { return owner; }
    public UUID session() { return session; }
    public long revision() { return revision; }
    public byte[] payload() { return payload.clone(); }
    int payloadLength() { return payload.length; }
    void writePayload(java.io.DataOutput out) throws java.io.IOException { out.write(payload); }

    /** Receivers must call this again on the game thread immediately before use. */
    public void requireScope(UUID activeWorld, UUID activeOwner, UUID activeSession) {
        if (!world.equals(activeWorld) || !owner.equals(activeOwner) || !session.equals(activeSession)) {
            throw new IllegalStateException("BRIDGE_SCOPE_CHANGED");
        }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof BridgeFrame)) return false;
        BridgeFrame value = (BridgeFrame) other;
        return kind == value.kind && revision == value.revision && operation.equals(value.operation)
                && world.equals(value.world) && owner.equals(value.owner) && session.equals(value.session)
                && Arrays.equals(payload, value.payload);
    }

    @Override public int hashCode() {
        return 31 * Objects.hash(kind, operation, world, owner, session, revision) + Arrays.hashCode(payload);
    }
}
