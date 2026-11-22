package com.bmp.web.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Web edge settings ({@code bmp.web.*}).
 *
 * @param problemBaseUri base of Problem {@code type} URIs, e.g. {@code https://errors.bmp.example}
 * @param maxRequestBytes request body limit (08-security.md §4), default 1 MB
 * @param slowRequestThreshold requests slower than this are logged at WARN
 * @param idempotencyTtl how long idempotency records are kept, default 24 h
 * @param corsAllowedOrigins strict CORS allow-list; empty means no cross-origin access
 */
@Validated
@ConfigurationProperties("bmp.web")
public record WebProperties(
        @DefaultValue("https://errors.bmp.example") @NotNull URI problemBaseUri,
        @DefaultValue("1048576") @Min(1024) long maxRequestBytes,
        @DefaultValue("1s") @NotNull Duration slowRequestThreshold,
        @DefaultValue("24h") @NotNull Duration idempotencyTtl,
        @DefaultValue List<String> corsAllowedOrigins) {

    public WebProperties {
        corsAllowedOrigins = List.copyOf(corsAllowedOrigins);
    }
}
