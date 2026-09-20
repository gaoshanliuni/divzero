package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.RuntimeInstance;

public record RuntimeInstanceMutationResult(boolean accepted, String errorCode, RuntimeInstance instance) {
    public static RuntimeInstanceMutationResult accepted(RuntimeInstance instance) {
        return new RuntimeInstanceMutationResult(true, "", instance);
    }

    public static RuntimeInstanceMutationResult rejected(RuntimeInstance instance, String errorCode) {
        return new RuntimeInstanceMutationResult(false, errorCode, instance);
    }
}
