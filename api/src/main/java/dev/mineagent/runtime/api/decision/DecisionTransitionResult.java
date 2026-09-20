package dev.mineagent.runtime.api.decision;

import java.util.Objects;

public record DecisionTransitionResult(boolean accepted, String errorCode, DecisionRequest request) {
    public DecisionTransitionResult {
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(request, "request");
    }

    public static DecisionTransitionResult accepted(DecisionRequest request) {
        return new DecisionTransitionResult(true, "", request);
    }

    public static DecisionTransitionResult rejected(DecisionRequest request, String errorCode) {
        return new DecisionTransitionResult(false, errorCode, request);
    }
}
