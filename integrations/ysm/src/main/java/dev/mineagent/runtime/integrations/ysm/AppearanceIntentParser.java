package dev.mineagent.runtime.integrations.ysm;

import java.util.Optional;

public final class AppearanceIntentParser {
    private static final int MAXIMUM_INPUT = 1_024;
    private static final String ID_PATTERN = "[A-Za-z0-9_.:-]+(?:/[A-Za-z0-9_.:-]+)*";

    private AppearanceIntentParser() {
    }

    public static Optional<Selection> parse(String input) {
        if (input == null || input.isBlank() || input.length() > MAXIMUM_INPUT) {
            return Optional.empty();
        }
        String model = "";
        String texture = "";
        String animation = "";
        String normalized = input.strip().replaceFirst("^外观\\s+", "");
        if (normalized.contains("|")) {
            String[] values = normalized.split("\\|", -1);
            if (values.length == 3) {
                model = values[0].strip();
                texture = values[1].strip();
                animation = values[2].strip();
            }
        } else {
            return AppearanceDecisionAnswer.resolve(null, normalized);
        }
        return checkedSelection(model, texture, animation);
    }

    static Optional<Selection> checkedSelection(String model, String texture, String animation) {
        if (!validId(model) || !validOptionalId(texture) || !validOptionalId(animation)) {
            return Optional.empty();
        }
        return Optional.of(new Selection(model, texture, animation));
    }

    private static boolean validOptionalId(String value) {
        return value != null && (value.isEmpty() || validId(value));
    }

    private static boolean validId(String value) {
        return value != null && value.length() <= 256 && value.matches(ID_PATTERN)
                && java.util.Arrays.stream(value.split("/")).noneMatch(segment -> segment.equals(".") || segment.equals(".."));
    }

    public record Selection(String modelId, String textureId, String animationId) {
    }
}
