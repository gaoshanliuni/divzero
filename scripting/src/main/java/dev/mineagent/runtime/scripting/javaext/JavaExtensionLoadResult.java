package dev.mineagent.runtime.scripting.javaext;

import dev.mineagent.runtime.api.packages.ActivationMode;

public record JavaExtensionLoadResult(
        ActivationMode activationMode,
        Object startResult,
        ClassLoader extensionClassLoader
) {
}
