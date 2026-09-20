package dev.mineagent.runtime.api.model;

import java.util.Objects;

public record ModelResponse(String providerId, String text, String requestedModel, String responseModel) {
    public ModelResponse(String providerId, String text) { this(providerId, text, "", ""); }
    public ModelResponse {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(text, "text");
        requestedModel = safeModelName(requestedModel);
        responseModel = safeModelName(responseModel);
    }

    /** Optional protocol metadata, never a place to reflect arbitrary response text or secrets. */
    public static String safeModelName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,255}")
                || value.toLowerCase(java.util.Locale.ROOT).contains("sk-")) return "";
        return value;
    }
}
