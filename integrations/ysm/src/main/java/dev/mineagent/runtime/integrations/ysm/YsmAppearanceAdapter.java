package dev.mineagent.runtime.integrations.ysm;

import dev.mineagent.runtime.api.appearance.AppearanceRequest;
import dev.mineagent.runtime.api.appearance.AppearanceResult;
import dev.mineagent.runtime.api.appearance.AppearanceStatus;

import java.util.Objects;

public final class YsmAppearanceAdapter {
    private static final String SUPPORTED_PREFIX = "2.6.5-neoforge+mc26.1";
    private final YsmRuntimeBridge bridge;

    public YsmAppearanceAdapter(YsmRuntimeBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public AppearanceResult apply(AppearanceRequest request) {
        if (!bridge.installed()) {
            return new AppearanceResult(AppearanceStatus.ABSENT, request.revision(), "YSM_ABSENT");
        }
        if (!bridge.version().startsWith(SUPPORTED_PREFIX)) {
            return new AppearanceResult(AppearanceStatus.UNSUPPORTED_ENV, request.revision(), "YSM_VERSION_UNSUPPORTED");
        }
        if (!bridge.runtimeAvailable()) {
            return new AppearanceResult(AppearanceStatus.UNSUPPORTED_ENV, request.revision(), "YSM_RUNTIME_UNAVAILABLE");
        }
        try {
            boolean applied = bridge.apply(request.playerId(), request.modelId(), request.textureId());
            if (applied && !request.animationId().isBlank()) {
                applied = bridge.playAnimation(request.playerId(), request.animationId());
            }
            return new AppearanceResult(
                    applied ? AppearanceStatus.READY : AppearanceStatus.ASSET_UNAVAILABLE,
                    request.revision(),
                    applied ? "" : "YSM_ASSET_UNAVAILABLE"
            );
        } catch (RuntimeException failure) {
            return new AppearanceResult(AppearanceStatus.ERROR, request.revision(), "YSM_APPLY_FAILED");
        }
    }
}
