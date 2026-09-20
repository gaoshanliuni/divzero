package dev.mineagent.runtime.integrations.ysm;

import java.util.UUID;

public interface YsmRuntimeBridge {
    boolean installed();

    String version();

    boolean runtimeAvailable();

    boolean apply(UUID playerId, String modelId, String textureId);

    default boolean playAnimation(UUID playerId, String animationId) {
        return false;
    }
}
