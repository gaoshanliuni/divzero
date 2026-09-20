package dev.mineagent.runtime.integrations.ysm;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.config.ConfigPatchResult;
import dev.mineagent.runtime.api.config.PanelSnapshot;
import dev.mineagent.runtime.core.config.ServerConfigService;

import java.util.Map;
import java.util.Objects;

public final class AppearanceWorldScope {
    private static final int RECHECK_PERSIST_ATTEMPTS = 3;
    public static final String RECHECK_REQUIRED_STATUS = "RECHECK_REQUIRED";
    public static final String UNKNOWN_WORLD_DIAGNOSTIC = "YSM_UNKNOWN_WORLD_RECHECK_REQUIRED";
    public static final String CROSS_WORLD_DIAGNOSTIC = "YSM_CROSS_WORLD_RECHECK_REQUIRED";
    public static final String PERSISTENCE_CONFLICT_DIAGNOSTIC = "YSM_WORLD_RECHECK_PERSISTENCE_CONFLICT";

    private AppearanceWorldScope() {
    }

    public static boolean usableIn(String storedWorldId, String currentWorldId) {
        return storedWorldId != null && !storedWorldId.isBlank() && storedWorldId.equals(currentWorldId);
    }

    public static Validation validatePersistedIntent(
            ServerConfigService config,
            String prefix,
            String currentWorldId
    ) {
        Objects.requireNonNull(config, "config");
        return validatePersistedIntent(new ConfigAccess() {
            @Override
            public PanelSnapshot snapshot() {
                return config.snapshot();
            }

            @Override
            public ConfigPatchResult apply(ConfigPatch patch) {
                return config.apply(patch, true);
            }
        }, prefix, currentWorldId);
    }

    static Validation validatePersistedIntent(
            ConfigAccess config,
            String prefix,
            String currentWorldId
    ) {
        Objects.requireNonNull(config, "config");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix is required");
        }
        PanelSnapshot lastValidatedSnapshot = null;
        for (int attempt = 0; attempt < RECHECK_PERSIST_ATTEMPTS; attempt++) {
            var snapshot = config.snapshot();
            lastValidatedSnapshot = snapshot;
            if (snapshot.values().getOrDefault(prefix + "model", "").isBlank()) {
                return new Validation(false, "", snapshot);
            }
            String storedWorldId = snapshot.values().getOrDefault(prefix + "appearanceWorldId", "");
            if (usableIn(storedWorldId, currentWorldId)) {
                String status = snapshot.values().getOrDefault(prefix + "appearanceStatus", "");
                String diagnostic = snapshot.values().getOrDefault(prefix + "appearanceDiagnostic", "");
                if (RECHECK_REQUIRED_STATUS.equals(status) && isWorldRecheckDiagnostic(diagnostic)) {
                    var recovered = config.apply(new ConfigPatch(snapshot.revision(), Map.of(
                            prefix + "appearanceStatus", "READY",
                            prefix + "appearanceDiagnostic", ""
                    )));
                    if (recovered.accepted()) {
                        return new Validation(true, "", recovered.snapshot());
                    }
                    if ("STALE_REVISION".equals(recovered.errorCode())) {
                        continue;
                    }
                    return new Validation(false, PERSISTENCE_CONFLICT_DIAGNOSTIC, recovered.snapshot());
                }
                return new Validation(true, "", snapshot);
            }

            String diagnostic = storedWorldId.isBlank()
                    ? UNKNOWN_WORLD_DIAGNOSTIC
                    : CROSS_WORLD_DIAGNOSTIC;
            if (RECHECK_REQUIRED_STATUS.equals(snapshot.values().get(prefix + "appearanceStatus"))
                    && diagnostic.equals(snapshot.values().get(prefix + "appearanceDiagnostic"))) {
                return new Validation(false, diagnostic, snapshot);
            }
            var result = config.apply(new ConfigPatch(snapshot.revision(), Map.of(
                    prefix + "appearanceStatus", RECHECK_REQUIRED_STATUS,
                    prefix + "appearanceDiagnostic", diagnostic
            )));
            if (result.accepted()) {
                return new Validation(false, diagnostic, snapshot);
            }
            if (!"STALE_REVISION".equals(result.errorCode())) {
                throw new IllegalStateException("could not persist appearance world recheck state: "
                        + result.errorCode());
            }
        }
        return new Validation(false, PERSISTENCE_CONFLICT_DIAGNOSTIC, lastValidatedSnapshot);
    }

    private static boolean isWorldRecheckDiagnostic(String diagnostic) {
        return UNKNOWN_WORLD_DIAGNOSTIC.equals(diagnostic)
                || CROSS_WORLD_DIAGNOSTIC.equals(diagnostic);
    }

    interface ConfigAccess {
        PanelSnapshot snapshot();

        ConfigPatchResult apply(ConfigPatch patch);
    }

    public record Validation(boolean usable, String diagnosticCode, PanelSnapshot snapshot) {
        public Validation {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
