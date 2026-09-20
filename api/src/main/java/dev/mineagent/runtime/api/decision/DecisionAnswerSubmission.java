package dev.mineagent.runtime.api.decision;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DecisionAnswerSubmission(
        UUID decisionId,
        long expectedRevision,
        UUID submissionId,
        List<String> selectedOptionIds,
        String customText,
        AnswerSource source
) {
    public DecisionAnswerSubmission {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(submissionId, "submissionId");
        Objects.requireNonNull(selectedOptionIds, "selectedOptionIds");
        Objects.requireNonNull(source, "source");
        selectedOptionIds = List.copyOf(selectedOptionIds);
        customText = customText == null ? "" : customText.strip();
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }
}
