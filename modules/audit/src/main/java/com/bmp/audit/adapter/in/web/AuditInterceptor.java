package com.bmp.audit.adapter.in.web;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.api.AuditWriter;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.web.request.RequestAttributes;
import com.bmp.web.security.AccessPolicy;
import com.bmp.web.security.BmpPrincipal;
import com.bmp.web.security.TenantContextFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records every write through the API (any method but GET, HEAD and OPTIONS) that reached a controller, including
 * refused ones, and every request a platform administrator makes for a tenant ({@code X-Tenant-Id}), reads included
 * (08-security.md §2): method and path template, resource, outcome status, actor, device, platform-administrator flag, IP,
 * user agent and request id. Idempotent replays are not recorded again. A request without a tenant is recorded in
 * the tenant named by its {@code tenantId} path variable (platform operations on a tenant), otherwise skipped.
 */
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String API_PREFIX = "/api/v1/";
    private static final int SERVER_ERROR = 500;

    private final AuditWriter writer;
    private final Clock clock;
    private final Counter lost;

    public AuditInterceptor(AuditWriter writer, Clock clock, MeterRegistry meters) {
        this.writer = writer;
        this.clock = clock;
        this.lost = Counter.builder("bmp.audit.lost")
                .description("API audit records that could not be written")
                .register(meters);
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) {
        boolean platformAdminActing =
                Boolean.TRUE.equals(request.getAttribute(TenantContextFilter.ACTING_AS_PLATFORM_ADMIN));
        if ((READS.contains(request.getMethod()) && !platformAdminActing)
                || !(handler instanceof HandlerMethod)
                || Boolean.TRUE.equals(request.getAttribute(RequestAttributes.IDEMPOTENT_REPLAY))) {
            return;
        }
        Map<String, String> variables = pathVariables(request);
        Optional<TenantId> tenant =
                TenantContext.current().or(() -> uuid(variables.get("tenantId")).map(TenantId::of));
        if (tenant.isEmpty()) {
            log.debug(
                    "API write without tenant not audited method={} uri={}",
                    request.getMethod(),
                    request.getRequestURI());
            return;
        }
        AuditEntry entry = entry(request, response, ex, variables);
        try {
            TenantContext.runWith(tenant.get(), () -> writer.record(entry));
        } catch (RuntimeException e) {
            // The change itself has completed; losing its audit record needs an operator (and raises the alert metric).
            lost.increment();
            log.atError()
                    .setCause(e)
                    .addKeyValue("action", entry.action())
                    .log("Audit record could not be written action={} status={}", entry.action(), entry.outcome());
        }
    }

    private AuditEntry entry(
            HttpServletRequest request,
            HttpServletResponse response,
            @Nullable Exception ex,
            Map<String, String> variables) {
        String template = attribute(request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = template != null ? template : request.getRequestURI();
        int status = ex != null && response.getStatus() < SERVER_ERROR ? SERVER_ERROR : response.getStatus();
        Optional<BmpPrincipal> principal = AccessPolicy.currentPrincipal();
        UserId actor = principal.map(BmpPrincipal::userId).orElse(null);
        DeviceId device = principal.map(BmpPrincipal::deviceId).orElse(null);
        boolean platformAdmin = principal.map(BmpPrincipal::isPlatformAdmin).orElse(false);
        return new AuditEntry(
                clock.instant(),
                AuditSource.API,
                request.getMethod() + " " + path,
                resourceType(path),
                resourceId(variables, response),
                status,
                actor,
                device,
                platformAdmin,
                null,
                null,
                request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT),
                attribute(request, RequestAttributes.REQUEST_ID),
                null);
    }

    /** The collection after {@code /api/v1/}, e.g. {@code users} for {@code /api/v1/users/{userId}/roles}. */
    static @Nullable String resourceType(String path) {
        if (!path.startsWith(API_PREFIX)) {
            return null;
        }
        String rest = path.substring(API_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** The first {@code ...Id} path variable, else the id at the end of a {@code Location} header. */
    private static @Nullable String resourceId(Map<String, String> variables, HttpServletResponse response) {
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            if (variable.getKey().endsWith("Id")) {
                return variable.getValue();
            }
        }
        String location = response.getHeader(HttpHeaders.LOCATION);
        return location != null ? location.substring(location.lastIndexOf('/') + 1) : null;
    }

    @SuppressWarnings("unchecked") // Spring stores the URI template variables as Map<String, String>
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static Optional<UUID> uuid(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static @Nullable String attribute(HttpServletRequest request, String name) {
        return request.getAttribute(name) instanceof String value ? value : null;
    }
}
