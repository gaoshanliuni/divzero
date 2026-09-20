package dev.mineagent.runtime.api.model;

import java.util.Set;

public interface ModelProvider {
    String id();

    Set<ModelCapability> capabilities();

    ModelResponse complete(ModelRequest request);
}
