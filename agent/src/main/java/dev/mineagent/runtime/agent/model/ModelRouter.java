package dev.mineagent.runtime.agent.model;

import dev.mineagent.runtime.api.model.ModelProvider;
import dev.mineagent.runtime.api.model.ModelRequest;
import dev.mineagent.runtime.api.model.ModelResponse;

import java.util.ArrayList;
import java.util.List;

public final class ModelRouter {
    private final List<ModelProvider> providers;

    public ModelRouter(List<ModelProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public ModelResponse complete(ModelRequest request) {
        var failures = new ArrayList<String>();
        for (ModelProvider provider : providers) {
            if (!provider.capabilities().contains(request.capability())) {
                continue;
            }
            try {
                return provider.complete(request);
            } catch (RuntimeException failure) {
                failures.add(provider.id() + ": " + failure.getMessage());
            }
        }
        String detail = failures.isEmpty() ? "没有 Provider 支持 " + request.capability() : String.join("; ", failures);
        throw new ModelRoutingException("模型路由失败: " + detail);
    }

    public ModelProvider select(ModelRequest request) {
        return providers.stream().filter(p -> p.capabilities().contains(request.capability())).findFirst()
                .orElseThrow(() -> new ModelRoutingException("PROVIDER_NOT_CONFIGURED_FOR_CAPABILITY"));
    }

    /** Runtime dispatches once; a timeout/HTTP error is not authorization to bill another Provider. */
    public ModelResponse completeOnce(ModelRequest request) {
        return select(request).complete(request);
    }
}
