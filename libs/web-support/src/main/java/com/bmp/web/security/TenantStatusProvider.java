package com.bmp.web.security;

import com.bmp.shared.id.TenantId;

/**
 * Answers whether a tenant may use the platform (SPI implemented by the {@code tenant} module, with a short cache).
 * The default implementation knows no tenant and therefore rejects every tenant request: fail closed.
 */
@FunctionalInterface
public interface TenantStatusProvider {

    TenantStatus statusOf(TenantId tenant);

    /** Lifecycle of a tenant as seen by request authorization. */
    enum TenantStatus {
        ACTIVE,
        /** Read-only: non-GET requests are rejected with {@code TENANT_SUSPENDED} (TEN-04). */
        SUSPENDED,
        CLOSED,
        UNKNOWN
    }
}
