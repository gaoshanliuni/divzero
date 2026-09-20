package dev.mineagent.runtime.core.scoreboard;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ScoreRuleDefinition(
        UUID ruleId,
        ScoreRuleKind kind,
        List<UUID> inputSourceIds,
        UUID outputSourceId,
        UUID ownerPackageId,
        Map<String, String> config,
        boolean enabled,
        long revision
) {
    public ScoreRuleDefinition {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(kind, "kind");
        inputSourceIds = List.copyOf(Objects.requireNonNull(inputSourceIds, "inputSourceIds"));
        Objects.requireNonNull(outputSourceId, "outputSourceId");
        Objects.requireNonNull(ownerPackageId, "ownerPackageId");
        config = Map.copyOf(Objects.requireNonNull(config, "config"));
        if (inputSourceIds.size() > 32 || config.size() > 128 || revision < 1) {
            throw new IllegalArgumentException("invalid score rule definition");
        }
    }
}
