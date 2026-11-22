package com.bmp.web.problem;

import com.bmp.shared.error.ErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;

/**
 * Localized titles and details of error codes. Detail templates may reference safe {@code details} by name, e.g.
 * {@code Location {locationId} is the last active location}.
 */
public class ProblemMessages {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    private final MessageSource messages;

    public ProblemMessages(MessageSource messages) {
        this.messages = messages;
    }

    public String title(ErrorCode code, Locale locale) {
        String title = messages.getMessage(code.titleKey(), null, null, locale);
        return title != null ? title : humanize(code.code());
    }

    /** The localized detail for {@code code}, or {@code null} when the bundle has no template for it. */
    public @Nullable String detail(ErrorCode code, Map<String, Object> details, Locale locale) {
        String template = messages.getMessage(code.detailKey(), null, null, locale);
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = details.get(matcher.group(1));
            matcher.appendReplacement(
                    out, Matcher.quoteReplacement(value != null ? value.toString() : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** {@code TENANT_NOT_FOUND} becomes {@code Tenant not found}. */
    static String humanize(String code) {
        String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
