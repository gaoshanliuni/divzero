package dev.mineagent.runtime.api.decision;

import java.util.Objects;

public record DecisionOption(String optionId, String title, String description) {
    public DecisionOption {
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(title, "title");
        description = description == null ? "" : description;
        if (optionId.isBlank() || title.isBlank()) {
            throw new IllegalArgumentException("optionId and title must not be blank");
        }
    }
}
