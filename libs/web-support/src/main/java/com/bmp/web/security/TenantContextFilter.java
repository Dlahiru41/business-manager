package com.bmp.web.security;

import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.ContextBinding;
import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.TenantId;
import com.bmp.web.problem.ProblemWriter;
import com.bmp.web.request.RequestAttributes;
import com.bmp.web.security.TenantStatusProvider.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated caller's tenant and actor for the rest of the request (08-security.md §2): the tenant comes
 * from the token's {@code tenant_id}; platform administrators may act for a tenant through {@code X-Tenant-Id} (logged
 * and audited). Rejects with 403: unknown or closed tenants ({@code TENANT_NOT_ACTIVE}), writes to suspended tenants
 * ({@code TENANT_SUSPENDED}, TEN-04) and revoked devices ({@code DEVICE_REVOKED}, IAM-03). Everything is cleared in
 * {@code finally} so nothing leaks to the next request on the same thread.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    /** Request attribute set when a platform administrator acts for a tenant. */
    public static final String ACTING_AS_PLATFORM_ADMIN = "bmp.actingAsPlatformAdmin";

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final TenantStatusProvider tenants;
    private final DeviceStatusProvider devices;
    private final ProblemWriter problems;

    public TenantContextFilter(TenantStatusProvider tenants, DeviceStatusProvider devices, ProblemWriter problems) {
        this.tenants = tenants;
        this.devices = devices;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof BmpAuthentication authentication)) {
            chain.doFilter(request, response);
            return;
        }
        BmpPrincipal principal = authentication.bmpPrincipal();
        Resolution resolution = resolveTenant(principal, request);
        if (resolution.rejection() != null) {
            reject(request, response, principal, resolution.rejection());
            return;
        }
        TenantId tenant = resolution.tenant();
        if (tenant != null) {
            ErrorCode rejection = checkTenantAndDevice(tenant, principal, request.getMethod());
            if (rejection != null) {
                reject(request, response, principal, rejection);
                return;
            }
        }
        proceed(request, response, chain, principal, tenant);
    }

    private void proceed(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            BmpPrincipal principal,
            @Nullable TenantId tenant)
            throws IOException, ServletException {
        Actor actor = new Actor(principal.userId(), principal.deviceId());
        request.setAttribute(RequestAttributes.USER_ID, principal.userId().toString());
        MDC.put(MdcKeys.USER_ID, principal.userId().toString());
        if (principal.deviceId() != null) {
            request.setAttribute(
                    RequestAttributes.DEVICE_ID, principal.deviceId().toString());
            MDC.put(MdcKeys.DEVICE_ID, principal.deviceId().toString());
        }
        try (ContextBinding _ = ActorContext.bind(actor)) {
            if (tenant == null) {
                chain.doFilter(request, response);
                return;
            }
            request.setAttribute(RequestAttributes.TENANT_ID, tenant.toString());
            MDC.put(MdcKeys.TENANT_ID, tenant.toString());
            try (ContextBinding _ = TenantContext.bind(tenant)) {
                chain.doFilter(request, response);
            }
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
            MDC.remove(MdcKeys.USER_ID);
            MDC.remove(MdcKeys.DEVICE_ID);
        }
    }

    private Resolution resolveTenant(BmpPrincipal principal, HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header == null) {
            if (principal.tenantId() == null && !principal.isPlatformAdmin()) {
                return Resolution.rejected(PlatformErrorCode.TENANT_NOT_ACTIVE);
            }
            return Resolution.of(principal.tenantId());
        }
        if (!principal.isPlatformAdmin()) {
            // Never trust a tenant from the request: only platform administrators may name one.
            return Resolution.rejected(PlatformErrorCode.ACCESS_DENIED);
        }
        try {
            TenantId tenant = TenantId.of(UUID.fromString(header.trim()));
            request.setAttribute(ACTING_AS_PLATFORM_ADMIN, true);
            log.atInfo()
                    .addKeyValue("actingTenantId", tenant.toString())
                    .log(
                            "Platform administrator acting for tenant {} {} {}",
                            tenant,
                            request.getMethod(),
                            request.getRequestURI());
            return Resolution.of(tenant);
        } catch (IllegalArgumentException e) {
            return Resolution.rejected(PlatformErrorCode.INVALID_PARAMETER);
        }
    }

    private @Nullable ErrorCode checkTenantAndDevice(TenantId tenant, BmpPrincipal principal, String method) {
        TenantStatus status = tenants.statusOf(tenant);
        if (status == TenantStatus.SUSPENDED && !READ_METHODS.contains(method)) {
            return PlatformErrorCode.TENANT_SUSPENDED;
        }
        if (status != TenantStatus.ACTIVE && status != TenantStatus.SUSPENDED) {
            return PlatformErrorCode.TENANT_NOT_ACTIVE;
        }
        if (principal.deviceId() != null && !devices.isActive(tenant, principal.deviceId())) {
            return PlatformErrorCode.DEVICE_REVOKED;
        }
        return null;
    }

    private void reject(
            HttpServletRequest request, HttpServletResponse response, BmpPrincipal principal, ErrorCode code)
            throws IOException {
        log.atWarn()
                .addKeyValue("code", code.code())
                .addKeyValue("userId", principal.userId().toString())
                .log(
                        "Request rejected code={} method={} uri={}",
                        code.code(),
                        request.getMethod(),
                        request.getRequestURI());
        problems.write(request, response, code, Map.of());
    }

    private record Resolution(
            @Nullable TenantId tenant, @Nullable ErrorCode rejection) {
        static Resolution of(@Nullable TenantId tenant) {
            return new Resolution(tenant, null);
        }

        static Resolution rejected(ErrorCode code) {
            return new Resolution(null, code);
        }
    }
}
