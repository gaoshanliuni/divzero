package dev.mineagent.runtime.api.ui;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Loader-independent UI identity and operation contract. No browser or game objects cross this boundary. */
public final class UiProtocol {
    private UiProtocol() {}
    /** Bounded declarations, not permission grants. Related task commands share a task-management capability. */
    public static final int MAX_CAPABILITIES=64;
    public enum ActorKind { PLAYER, AGENT }
    public enum Code {
        OK, APPLIED, OBSERVED, ACCEPTED, IN_PROGRESS, BUSY, CLOSED, EXPIRED, VIEW_NOT_RENDERED, PERMISSION_DENIED,
        PREVIEW_READ_ONLY, STALE_VIEW, STALE_TASK, STALE_PACKAGE, USER_INTERRUPTED,
        OPERATION_ID_REUSED, LEDGER_FULL, INVALID_REQUEST, UNSUPPORTED, TIMEOUT, FAILED,
        TARGET_NOT_FOUND, NOT_INTERACTABLE, STATE_CONFLICT
    }
    public enum Status { LOADING, RENDERED, CLOSED }

    public record Binding(String viewId, UUID ownerPackageId, long packageRevision, String packageVersion,
                          String entryPath, UUID worldId, UUID viewerPlayerId, UUID actorId, ActorKind actorKind,
                          UUID taskId, long taskRevision, String targetObjectId, boolean preview, Set<String> capabilities) {
        public Binding {
            requireText(viewId, 128); requireText(packageVersion, 64); requireText(entryPath, 256);
            Objects.requireNonNull(ownerPackageId); Objects.requireNonNull(worldId); Objects.requireNonNull(viewerPlayerId);
            Objects.requireNonNull(actorId); Objects.requireNonNull(actorKind);
            if (packageRevision < 0 || taskRevision < 0 || targetObjectId == null || targetObjectId.length() > 256)
                throw new IllegalArgumentException("UI_BINDING");
            capabilities = Set.copyOf(capabilities);
            if (capabilities.size() > MAX_CAPABILITIES) throw new IllegalArgumentException("UI_CAPABILITIES");
            capabilities.forEach(s -> requireText(s, 64));
        }
    }
    public record Session(UUID sessionId, UUID serverInstanceId, Binding binding, long pageGeneration,
                          long controlEpoch, long expiresAtMillis, Status status) {
        public Session {
            Objects.requireNonNull(sessionId); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(binding);
            Objects.requireNonNull(status);
            if (pageGeneration < 1 || controlEpoch < 1 || expiresAtMillis < 0) throw new IllegalArgumentException("UI_SESSION");
        }
    }
    public record Request(UUID operationId, UUID sessionId, long pageGeneration, long controlEpoch,
                          long taskRevision, String action, Map<String, String> arguments) {
        public Request {
            Objects.requireNonNull(operationId); Objects.requireNonNull(sessionId); requireText(action, 64);
            if (pageGeneration < 1 || controlEpoch < 1 || taskRevision < 0) throw new IllegalArgumentException("UI_REQUEST_VERSION");
            arguments = boundedMap(arguments);
        }
    }
    public record Receipt(UUID operationId, Code code, Map<String, String> values) {
        public Receipt { Objects.requireNonNull(operationId); Objects.requireNonNull(code); values = boundedMap(values); }
        public static Receipt of(UUID operation, Code code) { return new Receipt(operation, code, Map.of()); }
    }
    public static Map<String, String> boundedMap(Map<String, String> source) {
        Map<String, String> copy = Map.copyOf(source);
        if (copy.size() > 128) throw new IllegalArgumentException("UI_MESSAGE_SIZE");
        int size = 0;
        for (var e : copy.entrySet()) {
            requireText(e.getKey(), 128);
            if (e.getValue().length() > 24_000) throw new IllegalArgumentException("UI_MESSAGE_SIZE");
            size += e.getKey().length() + e.getValue().length();
        }
        if (size > 65_536) throw new IllegalArgumentException("UI_MESSAGE_SIZE");
        return copy;
    }
    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("UI_TEXT");
    }
}
