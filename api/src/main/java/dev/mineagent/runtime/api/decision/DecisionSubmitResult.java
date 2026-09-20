package dev.mineagent.runtime.api.decision;

import java.util.Objects;

public record DecisionSubmitResult(
        boolean accepted,
        boolean duplicate,
        String errorCode,
        DecisionRequest request
) {
    public DecisionSubmitResult {
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(request, "request");
    }

    public static DecisionSubmitResult accepted(DecisionRequest request, boolean duplicate) {
        return new DecisionSubmitResult(true, duplicate, "", request);
    }

    public static DecisionSubmitResult rejected(DecisionRequest request, String errorCode) {
        return new DecisionSubmitResult(false, false, errorCode, request);
    }
}
