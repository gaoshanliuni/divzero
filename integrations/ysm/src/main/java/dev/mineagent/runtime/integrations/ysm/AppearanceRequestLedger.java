package dev.mineagent.runtime.integrations.ysm;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded request ledger. A PENDING entry is installed by {@link #begin} before
 * execution is returned to the caller, so an equal concurrent request cannot
 * execute twice.
 */
public final class AppearanceRequestLedger {
    private static final UUID LEGACY_AGENT = new UUID(0, 0);

    private final int maximumEntries;
    private final LinkedHashMap<RequestKey, Entry> entries = new LinkedHashMap<>();

    public AppearanceRequestLedger(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    /** Compatibility overload for callers without appearance payload identity. */
    public synchronized Decision begin(UUID actorId, UUID requestId, long expectedRevision, long currentRevision) {
        return begin(actorId, requestId,
                new Fingerprint(LEGACY_AGENT, expectedRevision, "", "", ""), currentRevision);
    }

    public synchronized Decision begin(
            UUID actorId,
            UUID requestId,
            Fingerprint fingerprint,
            long currentRevision
    ) {
        RequestKey key = new RequestKey(actorId, requestId);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Entry existing = entries.get(key);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                return new Decision(Action.REQUEST_ID_REUSED,
                        new Outcome(false, "REQUEST_ID_REUSED", currentRevision));
            }
            return existing.pending()
                    ? new Decision(Action.IN_FLIGHT, existing.outcome())
                    : new Decision(Action.REPLAY, existing.outcome());
        }

        if (!makeRoom()) {
            return new Decision(Action.REJECT_CAPACITY,
                    new Outcome(false, "REQUEST_LEDGER_CAPACITY", currentRevision));
        }
        if (fingerprint.expectedRevision() != currentRevision) {
            Outcome stale = new Outcome(false, "STALE_REVISION", currentRevision);
            entries.put(key, new Entry(fingerprint, false, stale));
            return new Decision(Action.REJECT_STALE, stale);
        }

        Outcome pending = new Outcome(false, "PENDING", currentRevision);
        entries.put(key, new Entry(fingerprint, true, pending));
        return new Decision(Action.EXECUTE, pending);
    }

    /** Compatibility completion; it preserves the fingerprint installed by begin. */
    public synchronized void complete(UUID actorId, UUID requestId, Outcome outcome) {
        RequestKey key = new RequestKey(actorId, requestId);
        Entry existing = entries.get(key);
        Fingerprint fingerprint = existing == null
                ? new Fingerprint(LEGACY_AGENT, outcome.revision(), "", "", "")
                : existing.fingerprint();
        complete(actorId, requestId, fingerprint, outcome);
    }

    public synchronized void complete(
            UUID actorId,
            UUID requestId,
            Fingerprint fingerprint,
            Outcome outcome
    ) {
        RequestKey key = new RequestKey(actorId, requestId);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(outcome, "outcome");
        Entry existing = entries.get(key);
        if (existing != null && !existing.fingerprint().equals(fingerprint)) {
            throw new IllegalStateException("request fingerprint changed before completion");
        }
        if (existing == null && !makeRoom()) {
            throw new IllegalStateException("request ledger capacity exhausted by in-flight requests");
        }
        entries.put(key, new Entry(fingerprint, false, outcome));
    }

    public synchronized int size() {
        return entries.size();
    }

    private boolean makeRoom() {
        while (entries.size() >= maximumEntries) {
            var iterator = entries.entrySet().iterator();
            boolean removed = false;
            while (iterator.hasNext()) {
                if (!iterator.next().getValue().pending()) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return false;
            }
        }
        return true;
    }

    public enum Action {
        EXECUTE,
        REPLAY,
        REJECT_STALE,
        IN_FLIGHT,
        REQUEST_ID_REUSED,
        REJECT_CAPACITY
    }

    public record Decision(Action action, Outcome outcome) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record Outcome(boolean accepted, String errorCode, long revision) {
        public Outcome {
            errorCode = errorCode == null ? "" : errorCode;
        }
    }

    public record Fingerprint(
            UUID agentId,
            long expectedRevision,
            String modelId,
            String textureId,
            String animationId
    ) {
        public Fingerprint {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(modelId, "modelId");
            textureId = textureId == null ? "" : textureId;
            animationId = animationId == null ? "" : animationId;
        }
    }

    private record RequestKey(UUID actorId, UUID requestId) {
        private RequestKey {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    private record Entry(Fingerprint fingerprint, boolean pending, Outcome outcome) {
    }
}
