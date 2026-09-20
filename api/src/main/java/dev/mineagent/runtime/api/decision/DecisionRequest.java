package dev.mineagent.runtime.api.decision;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DecisionRequest(
        UUID decisionId,
        long revision,
        UUID recipientPlayerId,
        long taskRevision,
        DecisionKind kind,
        String title,
        String question,
        List<DecisionOption> options,
        SelectionMode selectionMode,
        int minSelections,
        int maxSelections,
        boolean allowCustomInput,
        DecisionStatus status
) {
    public DecisionRequest {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(selectionMode, "selectionMode");
        Objects.requireNonNull(status, "status");
        options = List.copyOf(options);
        if (revision < 0 || taskRevision < 0) {
            throw new IllegalArgumentException("revisions must not be negative");
        }
        if (minSelections < 0 || maxSelections < minSelections || maxSelections > options.size()) {
            throw new IllegalArgumentException("invalid selection limits");
        }
        if (selectionMode == SelectionMode.SINGLE && maxSelections > 1) {
            throw new IllegalArgumentException("single selection cannot accept more than one option");
        }
        var ids = new HashSet<String>();
        if (options.stream().anyMatch(option -> !ids.add(option.optionId()))) {
            throw new IllegalArgumentException("optionId must be unique");
        }
    }

    public DecisionRequest withStatus(DecisionStatus newStatus) {
        return new DecisionRequest(decisionId, revision + 1, recipientPlayerId, taskRevision, kind, title, question,
                options, selectionMode, minSelections, maxSelections, allowCustomInput, newStatus);
    }
}
