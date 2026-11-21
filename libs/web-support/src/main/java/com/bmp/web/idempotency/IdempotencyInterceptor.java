package com.bmp.web.idempotency;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.TenantId;
import com.bmp.web.request.RequestAttributes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Enforces {@link Idempotent} (03-api-standards.md §5): claims the key before the handler runs, replays stored
 * responses with {@code Idempotent-Replayed: true}, stores 2xx/4xx responses and releases the key on 5xx so the client
 * can retry. Keys are scoped to the authenticated tenant; requests without a tenant (platform administration) use the
 * {@link #PLATFORM_SCOPE}.
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** Scope of keys sent by callers without a tenant (platform administrators). */
    public static final TenantId PLATFORM_SCOPE = TenantId.of(new UUID(0L, 0L));

    private static final String CLAIM = IdempotencyInterceptor.class.getName() + ".claim";
    private static final List<String> REPLAYED_HEADERS =
            List.of(HttpHeaders.LOCATION, HttpHeaders.ETAG, HttpHeaders.CONTENT_TYPE);

    private final IdempotencyStore store;
    private final RequestHasher hasher;
    private final Clock clock;
    private final Duration ttl;
    private final Counter replays;
    private final JsonMapper json;

    public IdempotencyInterceptor(
            IdempotencyStore store,
            RequestHasher hasher,
            Clock clock,
            Duration ttl,
            MeterRegistry meters,
            JsonMapper json) {
        this.json = json;
        this.store = store;
        this.hasher = hasher;
        this.clock = clock;
        this.ttl = ttl;
        this.replays = Counter.builder("bmp.idempotency.replays")
                .description("Requests answered from a stored idempotent response")
                .register(meters);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Idempotent idempotent =
                handler instanceof HandlerMethod method ? method.getMethodAnnotation(Idempotent.class) : null;
        if (idempotent == null) {
            return true;
        }
        String key = requireKey(request.getHeader(HEADER));
        TenantId scope = TenantContext.current().orElse(PLATFORM_SCOPE);
        String hash = hasher.hash(request.getMethod(), request.getRequestURI(), body(request));
        Instant now = clock.instant();
        return switch (store.begin(scope, key, hash, now, now.plus(ttl))) {
            case IdempotencyStore.Started _ -> {
                request.setAttribute(CLAIM, new Claim(scope, key, List.of(idempotent.redact())));
                yield true;
            }
            case IdempotencyStore.Replay replay -> {
                replay(replay.response(), request, response);
                yield false;
            }
            case IdempotencyStore.KeyReused _ ->
                throw new BusinessRuleViolation(PlatformErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of());
            case IdempotencyStore.InProgress _ ->
                throw new BusinessRuleViolation(PlatformErrorCode.REQUEST_IN_PROGRESS, Map.of());
        };
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) {
        if (!(request.getAttribute(CLAIM) instanceof Claim claim)) {
            return;
        }
        int status = response.getStatus();
        if (status >= 500) {
            store.release(claim.scope(), claim.key());
            return;
        }
        store.complete(claim.scope(), claim.key(), capture(response, status, claim.redact()));
    }

    private static String requireKey(@Nullable String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required", Map.of());
        }
        try {
            return UUID.fromString(header.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key must be a UUID", Map.of());
        }
    }

    private static byte[] body(HttpServletRequest request) throws IOException {
        IdempotencyFilter.CachedBodyRequest cached =
                WebUtils.getNativeRequest(request, IdempotencyFilter.CachedBodyRequest.class);
        return cached != null ? cached.body() : new byte[0];
    }

    private void replay(
            IdempotencyStore.StoredResponse stored, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        replays.increment();
        request.setAttribute(RequestAttributes.IDEMPOTENT_REPLAY, true);
        response.setStatus(stored.status());
        stored.headers().forEach(response::setHeader);
        response.setHeader(REPLAYED_HEADER, "true");
        String body = stored.body();
        if (body != null) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private IdempotencyStore.StoredResponse capture(HttpServletResponse response, int status, List<String> redact) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : REPLAYED_HEADERS) {
            String value = response.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        ContentCachingResponseWrapper cached =
                WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        byte[] content = cached != null ? cached.getContentAsByteArray() : new byte[0];
        String body = content.length == 0 ? null : new String(content, StandardCharsets.UTF_8);
        return new IdempotencyStore.StoredResponse(status, headers, redacted(body, redact));
    }

    /** Removes one-time secrets from the copy kept for replay; the live response is unaffected. */
    private @Nullable String redacted(@Nullable String body, List<String> fields) {
        if (body == null || fields.isEmpty()) {
            return body;
        }
        try {
            JsonNode tree = json.readTree(body);
            if (tree instanceof ObjectNode object) {
                fields.forEach(object::remove);
                return json.writeValueAsString(object);
            }
            return body;
        } catch (JacksonException notJson) {
            return null;
        }
    }

    private record Claim(TenantId scope, String key, List<String> redact) {}
}
