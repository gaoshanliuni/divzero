package dev.mineagent.runtime.core.packages;

import java.util.Set;

public record ProductionContentCatalog(Set<String> entryIds) {
    public ProductionContentCatalog {
        entryIds = Set.copyOf(entryIds);
    }

    public static ProductionContentCatalog defaults() {
        return new ProductionContentCatalog(Set.of(
                "runtime_anchor",
                "runtime_item",
                "runtime_projectile",
                "runtime_display_surface",
                "automation_console",
                "media_screen"
        ));
    }
}
