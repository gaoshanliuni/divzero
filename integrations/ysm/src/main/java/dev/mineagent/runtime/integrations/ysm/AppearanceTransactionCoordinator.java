package dev.mineagent.runtime.integrations.ysm;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.config.ConfigPatchResult;
import dev.mineagent.runtime.api.config.PanelSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-phase appearance transaction. Prepare advances the agent-scoped revision
 * before native mutation. Finalize may retry unrelated global CAS conflicts,
 * but every attempt revalidates the same agent revision and request identity.
 */
public final class AppearanceTransactionCoordinator {
    public static final String FINALIZE_CONFLICT = "APPEARANCE_FINALIZE_CONFLICT";
    public static final String ROLLBACK_FAILED = "YSM_ROLLBACK_FAILED";

    private final ConfigAccess config;
    private final NativeAccess nativeAccess;
    private final int persistenceAttempts;
    private final ConcurrentHashMap<UUID, Object> agentLocks = new ConcurrentHashMap<>();

    public AppearanceTransactionCoordinator(
            ConfigAccess config,
            NativeAccess nativeAccess,
            int persistenceAttempts
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
        if (persistenceAttempts < 1) {
            throw new IllegalArgumentException("persistenceAttempts must be positive");
        }
        this.persistenceAttempts = persistenceAttempts;
    }

    public AppearanceRequestLedger.Outcome execute(Command command) {
        Objects.requireNonNull(command, "command");
        Object lock = agentLocks.computeIfAbsent(command.agentId(), ignored -> new Object());
        synchronized (lock) {
            return executeLocked(command);
        }
    }

    private AppearanceRequestLedger.Outcome executeLocked(Command command) {
        PanelSnapshot initial = config.snapshot();
        String prefix = prefix(command.agentId());
        long currentRevision = appearanceRevision(initial, prefix);
        if (currentRevision != command.expectedRevision()) {
            return outcome(false, "STALE_REVISION", currentRevision);
        }

        AppearanceCommitPlan.Selection persistedBefore = activeSelection(initial, prefix);
        AppearanceCommitPlan.Selection nativeBefore = safeRead(command.agentId()).orElse(persistedBefore);
        PersistResult prepared = prepare(command);
        if (!prepared.accepted()) {
            return outcome(false, prepared.errorCode(), prepared.appearanceRevision());
        }

        long transactionRevision = command.expectedRevision() + 1;
        NativeResult applied;
        try {
            applied = Objects.requireNonNull(
                    nativeAccess.apply(command.agentId(), command.selection()), "native result");
        } catch (RuntimeException | LinkageError failure) {
            applied = new NativeResult(false, "YSM_APPLY_FAILED");
        }
        Optional<AppearanceCommitPlan.Selection> appliedReadback = Optional.empty();
        if (applied.applied()) {
            appliedReadback = safeRead(command.agentId());
            if (appliedReadback.isEmpty()
                    || !matchesRequested(command.selection(), appliedReadback.orElseThrow())) {
                applied = new NativeResult(false, "YSM_READBACK_MISMATCH");
            }
        }
        if (!applied.applied()) {
            String preflightStatus = preflightFailureStatus(applied.diagnosticCode());
            if (!preflightStatus.isEmpty()) {
                persistTerminal(command, preflightStatus, applied.diagnosticCode(), nativeBefore);
                return outcome(false, applied.diagnosticCode(), transactionRevision);
            }
            return compensate(command, nativeBefore, applied.diagnosticCode(), transactionRevision);
        }

        PersistResult finalized = persistTerminal(
                command, "READY", "", appliedReadback.orElseThrow());
        if (finalized.accepted()) {
            return outcome(true, "", transactionRevision);
        }
        if (finalized.agentSuperseded()) {
            return reconcileSuperseded(command, finalized.appearanceRevision());
        }
        String failureCode = finalized.errorCode().isBlank()
                ? FINALIZE_CONFLICT : finalized.errorCode();
        return compensate(command, nativeBefore, failureCode, transactionRevision);
    }

    private AppearanceRequestLedger.Outcome reconcileSuperseded(Command command, long observedRevision) {
        String prefix = prefix(command.agentId());
        long latestRevision = observedRevision;
        for (int attempt = 0; attempt < persistenceAttempts; attempt++) {
            PanelSnapshot targetSnapshot = config.snapshot();
            long targetRevision = appearanceRevision(targetSnapshot, prefix);
            AppearanceCommitPlan.Selection target = activeSelection(targetSnapshot, prefix);
            latestRevision = targetRevision;

            Optional<AppearanceCommitPlan.Selection> readback = safeRead(command.agentId());
            if (readback.isEmpty() || !readback.orElseThrow().equals(target)) {
                try {
                    nativeAccess.apply(command.agentId(), target);
                } catch (RuntimeException | LinkageError ignored) {
                    // Readback below is authoritative.
                }
                readback = safeRead(command.agentId());
            }

            PanelSnapshot verifiedSnapshot = config.snapshot();
            long verifiedRevision = appearanceRevision(verifiedSnapshot, prefix);
            AppearanceCommitPlan.Selection verifiedTarget = activeSelection(verifiedSnapshot, prefix);
            latestRevision = verifiedRevision;
            if (verifiedRevision != targetRevision || !verifiedTarget.equals(target)) {
                continue;
            }
            if (readback.isPresent() && readback.orElseThrow().equals(target)) {
                return outcome(false, "STALE_REVISION", targetRevision);
            }

            PersistResult inconsistent = persistInconsistentActual(
                    command.agentId(), targetRevision, target, readback);
            latestRevision = inconsistent.appearanceRevision();
            if (inconsistent.agentSuperseded()) {
                continue;
            }
            return outcome(false, ROLLBACK_FAILED, latestRevision);
        }
        return outcome(false, ROLLBACK_FAILED, latestRevision);
    }

    private PersistResult persistInconsistentActual(
            UUID agentId,
            long requiredRevision,
            AppearanceCommitPlan.Selection requiredActive,
            Optional<AppearanceCommitPlan.Selection> actual
    ) {
        String prefix = prefix(agentId);
        for (int attempt = 0; attempt < persistenceAttempts; attempt++) {
            PanelSnapshot snapshot = config.snapshot();
            long current = appearanceRevision(snapshot, prefix);
            if (current != requiredRevision || !activeSelection(snapshot, prefix).equals(requiredActive)) {
                return PersistResult.superseded(current);
            }
            var updates = new LinkedHashMap<String, String>();
            updates.put(prefix + "appearanceStatus", "INCONSISTENT");
            updates.put(prefix + "appearanceDiagnostic", ROLLBACK_FAILED);
            actual.ifPresent(selection -> {
                updates.put(prefix + "model", selection.modelId());
                updates.put(prefix + "texture", selection.textureId());
                updates.put(prefix + "animation", selection.animationId());
            });
            ConfigPatchResult result = config.apply(new ConfigPatch(snapshot.revision(), updates));
            if (result.accepted()) {
                return PersistResult.accepted(current);
            }
            if (!"STALE_REVISION".equals(result.errorCode())) {
                return PersistResult.failed(result.errorCode(), current);
            }
        }
        return PersistResult.failed(FINALIZE_CONFLICT, requiredRevision);
    }

    private PersistResult prepare(Command command) {
        String prefix = prefix(command.agentId());
        long nextRevision = command.expectedRevision() + 1;
        for (int attempt = 0; attempt < persistenceAttempts; attempt++) {
            PanelSnapshot snapshot = config.snapshot();
            long current = appearanceRevision(snapshot, prefix);
            if (current != command.expectedRevision()) {
                return PersistResult.superseded(current);
            }
            var updates = baseUpdates(command, "APPLYING", "");
            updates.put(prefix + "appearanceRevision", Long.toString(nextRevision));
            ConfigPatchResult result = config.apply(new ConfigPatch(snapshot.revision(), updates));
            if (result.accepted()) {
                return PersistResult.accepted(nextRevision);
            }
            if (!"STALE_REVISION".equals(result.errorCode())) {
                return PersistResult.failed(result.errorCode(), current);
            }
        }
        return PersistResult.failed("APPEARANCE_PREPARE_CONFLICT", command.expectedRevision());
    }

    private PersistResult persistTerminal(
            Command command,
            String status,
            String diagnostic,
            AppearanceCommitPlan.Selection active
    ) {
        String prefix = prefix(command.agentId());
        long transactionRevision = command.expectedRevision() + 1;
        for (int attempt = 0; attempt < persistenceAttempts; attempt++) {
            PanelSnapshot snapshot = config.snapshot();
            long current = appearanceRevision(snapshot, prefix);
            if (current != transactionRevision || !belongsTo(command, snapshot, prefix)) {
                return PersistResult.superseded(current);
            }
            var updates = baseUpdates(command, status, diagnostic);
            updates.put(prefix + "appearanceRevision", Long.toString(transactionRevision));
            updates.put(prefix + "model", active.modelId());
            updates.put(prefix + "texture", active.textureId());
            updates.put(prefix + "animation", active.animationId());
            updates.put(prefix + "appearanceWorldId", command.worldId());
            ConfigPatchResult result = config.apply(new ConfigPatch(snapshot.revision(), updates));
            if (result.accepted()) {
                return PersistResult.accepted(transactionRevision);
            }
            if (!"STALE_REVISION".equals(result.errorCode())) {
                return PersistResult.failed(result.errorCode(), current);
            }
        }
        return PersistResult.failed(FINALIZE_CONFLICT, transactionRevision);
    }

    private AppearanceRequestLedger.Outcome compensate(
            Command command,
            AppearanceCommitPlan.Selection nativeBefore,
            String originalFailure,
            long transactionRevision
    ) {
        boolean restoreReported = false;
        try {
            restoreReported = nativeAccess.apply(command.agentId(), nativeBefore).applied();
        } catch (RuntimeException | LinkageError ignored) {
            // Readback below is authoritative.
        }
        Optional<AppearanceCommitPlan.Selection> readback = safeRead(command.agentId());
        if (restoreReported && readback.isPresent() && readback.orElseThrow().equals(nativeBefore)) {
            String status = "YSM_ASSET_UNAVAILABLE".equals(originalFailure)
                    ? "ASSET_UNAVAILABLE" : "ERROR";
            PersistResult terminal = persistTerminal(
                    command, status, originalFailure, nativeBefore);
            return compensationOutcome(command, terminal, originalFailure, transactionRevision);
        }

        AppearanceCommitPlan.Selection actual = readback.orElse(nativeBefore);
        PersistResult terminal = persistTerminal(
                command, "INCONSISTENT", ROLLBACK_FAILED, actual);
        return compensationOutcome(command, terminal, ROLLBACK_FAILED, transactionRevision);
    }

    private AppearanceRequestLedger.Outcome compensationOutcome(
            Command command,
            PersistResult terminal,
            String compensatedFailure,
            long transactionRevision
    ) {
        if (terminal.accepted()) {
            return outcome(false, compensatedFailure, transactionRevision);
        }
        if (terminal.agentSuperseded()) {
            return reconcileSuperseded(command, terminal.appearanceRevision());
        }
        return outcome(false, ROLLBACK_FAILED, terminal.appearanceRevision());
    }

    private Optional<AppearanceCommitPlan.Selection> safeRead(UUID agentId) {
        try {
            return Objects.requireNonNull(nativeAccess.read(agentId), "native readback");
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    private static LinkedHashMap<String, String> baseUpdates(
            Command command,
            String status,
            String diagnostic
    ) {
        String prefix = prefix(command.agentId());
        var updates = new LinkedHashMap<String, String>();
        updates.put(prefix + "requestedModel", command.selection().modelId());
        updates.put(prefix + "requestedTexture", command.selection().textureId());
        updates.put(prefix + "requestedAnimation", command.selection().animationId());
        updates.put(prefix + "appearanceProvider", command.provider());
        updates.put(prefix + "appearanceProviderVersion", command.providerVersion());
        updates.put(prefix + "appearanceStatus", status);
        updates.put(prefix + "appearanceDiagnostic", diagnostic);
        return updates;
    }

    private static boolean belongsTo(Command command, PanelSnapshot snapshot, String prefix) {
        Map<String, String> values = snapshot.values();
        return command.selection().modelId().equals(values.get(prefix + "requestedModel"))
                && command.selection().textureId().equals(values.get(prefix + "requestedTexture"))
                && command.selection().animationId().equals(values.get(prefix + "requestedAnimation"))
                && "APPLYING".equals(values.get(prefix + "appearanceStatus"));
    }

    private static boolean matchesRequested(
            AppearanceCommitPlan.Selection requested,
            AppearanceCommitPlan.Selection actual
    ) {
        return requested.modelId().equals(actual.modelId())
                // Empty means the pinned native adapter chooses the model default; persist its readback, not an invented ID.
                && (requested.textureId().isEmpty() || requested.textureId().equals(actual.textureId()))
                && (requested.animationId().isBlank()
                || requested.animationId().equals(actual.animationId()));
    }

    private static String preflightFailureStatus(String diagnostic) {
        return switch (diagnostic) {
            case "YSM_ABSENT" -> "ABSENT";
            case "YSM_VERSION_UNSUPPORTED", "YSM_RUNTIME_UNAVAILABLE" -> "UNSUPPORTED_ENV";
            default -> "";
        };
    }

    private static AppearanceCommitPlan.Selection activeSelection(PanelSnapshot snapshot, String prefix) {
        return new AppearanceCommitPlan.Selection(
                snapshot.values().getOrDefault(prefix + "model", "default"),
                snapshot.values().getOrDefault(prefix + "texture", ""),
                snapshot.values().getOrDefault(prefix + "animation", ""));
    }

    private static long appearanceRevision(PanelSnapshot snapshot, String prefix) {
        try {
            return Long.parseLong(snapshot.values().getOrDefault(prefix + "appearanceRevision", "0"));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String prefix(UUID agentId) {
        return "agent." + agentId + ".";
    }

    private static AppearanceRequestLedger.Outcome outcome(boolean accepted, String code, long revision) {
        return new AppearanceRequestLedger.Outcome(accepted, code, revision);
    }

    public interface ConfigAccess {
        PanelSnapshot snapshot();

        ConfigPatchResult apply(ConfigPatch patch);
    }

    public interface NativeAccess {
        Optional<AppearanceCommitPlan.Selection> read(UUID agentId);

        NativeResult apply(UUID agentId, AppearanceCommitPlan.Selection selection);
    }

    public record NativeResult(boolean applied, String diagnosticCode) {
        public NativeResult {
            diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
        }
    }

    public record Command(
            UUID agentId,
            long expectedRevision,
            AppearanceCommitPlan.Selection selection,
            String worldId,
            String provider,
            String providerVersion
    ) {
        public Command {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(selection, "selection");
            worldId = worldId == null ? "" : worldId;
            provider = provider == null ? "" : provider;
            providerVersion = providerVersion == null ? "" : providerVersion;
        }
    }

    private record PersistResult(
            boolean accepted,
            boolean agentSuperseded,
            String errorCode,
            long appearanceRevision
    ) {
        private static PersistResult accepted(long revision) {
            return new PersistResult(true, false, "", revision);
        }

        private static PersistResult failed(String errorCode, long revision) {
            return new PersistResult(false, false, errorCode == null ? "" : errorCode, revision);
        }

        private static PersistResult superseded(long revision) {
            return new PersistResult(false, true, "STALE_REVISION", revision);
        }
    }
}
