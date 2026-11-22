package com.bmp.web.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in log text (05-logging-and-observability.md §4): JWTs, bearer tokens,
 * password/secret key-value pairs, card-number-like digit runs, e-mail addresses and Sri Lankan phone numbers. Ids
 * (UUIDs) are kept, because logs must carry ids instead of personal data.
 */
public final class LogMasker {

    static final String MASK = "***";

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"), "eyJ" + MASK),
            new Rule(Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 " + MASK),
            new Rule(
                    Pattern.compile(
                            "(?i)\\b(password|passwd|pwd|secret|client_secret|api[_-]?key|token|pin)(\"?\\s*[=:]\\s*\"?)[^\\s\",;&}]+"),
                    "$1$2" + MASK),
            new Rule(Pattern.compile("\\b(?:\\d{4}[ -]){3}\\d{1,7}\\b"), MASK + "PAN" + MASK),
            new Rule(Pattern.compile("\\b\\d{13,19}\\b"), MASK + "PAN" + MASK),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), MASK + "@" + MASK),
            new Rule(Pattern.compile("\\+94[ -]?\\d{2}[ -]?\\d{3}[ -]?\\d{4}\\b"), "+94" + MASK),
            new Rule(Pattern.compile("\\b07\\d{8}\\b"), "07" + MASK));

    private LogMasker() {}

    /** The text with every sensitive fragment replaced. */
    public static String mask(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(masked);
            if (matcher.find()) {
                masked = matcher.replaceAll(rule.replacement());
            }
        }
        return masked;
    }
}
