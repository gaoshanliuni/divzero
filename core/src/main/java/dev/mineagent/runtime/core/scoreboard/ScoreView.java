package dev.mineagent.runtime.core.scoreboard;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ScoreView(
        UUID viewId,
        UUID sourceId,
        ScoreViewKind kind,
        UUID ownerPackageId,
        ScoreAudience audience,
        Map<String, String> layout,
        ScoreViewTarget target,
        boolean enabled,
        long revision,
        long updatedAtEpochMillis
) {
    public ScoreView {
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ownerPackageId, "ownerPackageId");
        Objects.requireNonNull(audience, "audience");
        layout = Map.copyOf(Objects.requireNonNull(layout, "layout"));
        Objects.requireNonNull(target, "target");
        if (layout.size() > 128 || revision < 1 || layout.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 64
                        || entry.getValue() == null || entry.getValue().length() > 2_048)) {
            throw new IllegalArgumentException("invalid score view");
        }
    }
}
