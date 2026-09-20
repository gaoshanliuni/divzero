package dev.mineagent.runtime.core.audit;

import java.util.List;
import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s]+"), "$1[REDACTED]"),
            new Rule(Pattern.compile("(?i)((?:api[_-]?key|token|secret|password)\\s*[=:]\\s*)[^\\s&,]+"), "$1[REDACTED]"),
            new Rule(Pattern.compile("(?i)(\"(?:apiKey|api_key|token|secret|password)\"\\s*:\\s*\")[^\"]*(\")"),
                    "$1[REDACTED]$2")
    );

    private SecretRedactor() {
    }

    public static String redact(String value) {
        String result = value == null ? "" : value;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private record Rule(Pattern pattern, String replacement) {
    }
}
