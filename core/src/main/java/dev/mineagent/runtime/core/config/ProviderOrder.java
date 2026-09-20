package dev.mineagent.runtime.core.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Order chooses an eligible configured Provider before dispatch, not failover after a paid request. */
public final class ProviderOrder {
    public static final String DEFAULT = "openai-compatible,ollama";
    private ProviderOrder() {}

    public static List<String> parse(String value) {
        if (value == null) throw new IllegalArgumentException("PROVIDER_ORDER_INVALID");
        var order = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
        if (order.size() != 2 || !Set.copyOf(order).equals(Set.of("openai-compatible", "ollama"))) {
            throw new IllegalArgumentException("PROVIDER_ORDER_INVALID");
        }
        return order;
    }
}
