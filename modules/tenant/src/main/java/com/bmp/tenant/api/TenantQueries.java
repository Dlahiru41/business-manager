package com.bmp.tenant.api;

import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.dto.TenantSummary;
import java.util.Optional;

/** Read-only questions other modules may ask the tenant module. */
public interface TenantQueries {

    /** The tenant, read in its own tenant context. */
    Optional<TenantSummary> findTenant(TenantId tenant);

    /** Whether the location exists and is active in the current tenant. */
    boolean isActiveLocation(LocationId location);

    /**
     * The effective value of a business setting of the current tenant (its default when never set), as JSON text,
     * e.g. {@code true} or {@code "LKR"}; empty for an unknown group or key.
     */
    Optional<String> setting(String group, String key);
}
