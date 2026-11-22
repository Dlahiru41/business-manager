package com.bmp.shared.context;

import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.id.TenantId;
import java.util.Optional;

/**
 * The tenant the current unit of work belongs to. Set from the authenticated principal by the security filter (never
 * from the request body or URL) or from the event being processed. Persistence refuses to touch tenant-owned data
 * without it ({@code TENANT_CONTEXT_MISSING}); it never defaults to "all tenants".
 */
public final class TenantContext {

    private static final ScopedHolder<TenantId> HOLDER = new ScopedHolder<>("TenantContext");

    private TenantContext() {}

    public static Optional<TenantId> current() {
        return HOLDER.current();
    }

    /** The current tenant, or {@code TechnicalException(TENANT_CONTEXT_MISSING)} when none is set. */
    public static TenantId require() {
        return current().orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING));
    }

    /** Runs {@code action} with {@code tenant} bound; the binding ends when the action returns. */
    public static void runWith(TenantId tenant, Runnable action) {
        HOLDER.runWith(tenant, action);
    }

    /** Calls {@code action} with {@code tenant} bound and returns its result. */
    public static <R, X extends Throwable> R callWith(TenantId tenant, ScopedValue.CallableOp<? extends R, X> action)
            throws X {
        return HOLDER.callWith(tenant, action);
    }

    /**
     * Thread-local bridge for servlet filters. Close the returned binding in a {@code finally} (try-with-resources) so
     * the tenant never leaks to the next request handled by the same thread.
     */
    public static ContextBinding bind(TenantId tenant) {
        return HOLDER.bind(tenant);
    }
}
