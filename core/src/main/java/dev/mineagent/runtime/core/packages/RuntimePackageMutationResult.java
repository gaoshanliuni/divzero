package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.RuntimePackage;

public record RuntimePackageMutationResult(boolean accepted, String errorCode, RuntimePackage runtimePackage) {
    public static RuntimePackageMutationResult accepted(RuntimePackage runtimePackage) {
        return new RuntimePackageMutationResult(true, "", runtimePackage);
    }

    public static RuntimePackageMutationResult rejected(RuntimePackage runtimePackage, String errorCode) {
        return new RuntimePackageMutationResult(false, errorCode, runtimePackage);
    }
}
