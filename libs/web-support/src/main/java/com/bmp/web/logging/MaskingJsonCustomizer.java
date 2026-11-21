package com.bmp.web.logging;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks every string member (message, MDC values, key-value pairs, stack traces) of structured (ECS JSON) log lines.
 * Configured with {@code logging.structured.json.customizer}.
 */
public class MaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, MaskingJsonCustomizer::mask));
    }

    private static @Nullable String mask(@Nullable String value) {
        return value != null ? LogMasker.mask(value) : null;
    }
}
