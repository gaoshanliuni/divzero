package dev.mineagent.runtime.api.appearance;

import java.util.Objects;
import java.util.UUID;

public record AppearanceRequest(
        UUID playerId,
        String modelId,
        String textureId,
        String animationId,
        long revision
) {
    public AppearanceRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(modelId, "modelId");
        textureId = textureId == null ? "" : textureId;
        animationId = animationId == null ? "" : animationId;
        if (modelId.isBlank() || revision < 0) {
            throw new IllegalArgumentException("invalid appearance request");
        }
    }

    public AppearanceRequest(UUID playerId, String modelId, String textureId, long revision) {
        this(playerId, modelId, textureId, "", revision);
    }
}
