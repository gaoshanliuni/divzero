package dev.mineagent.runtime.integrations.ysm;

import dev.mineagent.runtime.api.appearance.AppearanceRequest;
import dev.mineagent.runtime.api.appearance.AppearanceStatus;

import java.util.Objects;
import java.util.UUID;

public final class AppearanceLifecycleCoordinator {
    private final YsmRuntimeBridge bridge;

    public AppearanceLifecycleCoordinator(YsmRuntimeBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public Result reapply(Intent intent, String reason) {
        Objects.requireNonNull(intent, "intent");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        var result = new YsmAppearanceAdapter(bridge).apply(new AppearanceRequest(
                intent.agentId(), intent.modelId(), intent.textureId(), intent.animationId(), intent.revision()));
        return new Result(result.status() == AppearanceStatus.READY, result.diagnosticCode(),
                result.revision(), reason);
    }

    public record Intent(
            UUID agentId,
            String modelId,
            String textureId,
            String animationId,
            long revision
    ) {
    }

    public record Result(boolean applied, String diagnosticCode, long revision, String reason) {
    }
}
