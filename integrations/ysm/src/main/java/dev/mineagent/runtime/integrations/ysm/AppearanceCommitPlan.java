package dev.mineagent.runtime.integrations.ysm;

import dev.mineagent.runtime.api.appearance.AppearanceResult;
import dev.mineagent.runtime.api.appearance.AppearanceStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the atomic persisted representation of requested and applied appearance state.
 */
public final class AppearanceCommitPlan {
    private AppearanceCommitPlan() {
    }

    public static Map<String, String> updates(
            String prefix,
            Selection requested,
            AppearanceResult result,
            String worldId,
            String provider,
            String providerVersion
    ) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix is required");
        }
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(result, "result");
        var updates = new LinkedHashMap<String, String>();
        updates.put(prefix + "requestedModel", requested.modelId());
        updates.put(prefix + "requestedTexture", requested.textureId());
        updates.put(prefix + "requestedAnimation", requested.animationId());
        updates.put(prefix + "appearanceProvider", provider == null ? "" : provider);
        updates.put(prefix + "appearanceProviderVersion", providerVersion == null ? "" : providerVersion);
        updates.put(prefix + "appearanceStatus", result.status().name());
        updates.put(prefix + "appearanceDiagnostic", result.diagnosticCode());
        updates.put(prefix + "appearanceRevision", Long.toString(result.revision()));
        if (result.status() == AppearanceStatus.READY) {
            updates.put(prefix + "model", requested.modelId());
            updates.put(prefix + "texture", requested.textureId());
            updates.put(prefix + "animation", requested.animationId());
            updates.put(prefix + "appearanceWorldId", worldId == null ? "" : worldId);
        }
        return Map.copyOf(updates);
    }

    public record Selection(String modelId, String textureId, String animationId) {
        public Selection {
            Objects.requireNonNull(modelId, "modelId");
            textureId = textureId == null ? "" : textureId;
            animationId = animationId == null ? "" : animationId;
            if (modelId.isBlank()) {
                throw new IllegalArgumentException("modelId is required");
            }
        }
    }
}
