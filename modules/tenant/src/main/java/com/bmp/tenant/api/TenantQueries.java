package com.bmp.tenant.api;

import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.dto.ReceiptTemplateInfo;
import com.bmp.tenant.api.dto.TenantSummary;
import java.util.Optional;

/** Read-only questions other modules may ask the tenant module. */
public interface TenantQueries {

    /** The tenant, read in its own tenant context (a transaction of its own). */
    Optional<TenantSummary> findTenant(TenantId tenant);

    /**
     * The tenant of the current context, read in the caller's transaction (no second connection), e.g. for its
     * currency and time zone on a request path.
     */
    Optional<TenantSummary> currentTenant();

    /** Whether the location exists and is active in the current tenant. */
    boolean isActiveLocation(LocationId location);

    /**
     * The effective value of a business setting of the current tenant (its default when never set), as JSON text,
     * e.g. {@code true} or {@code "LKR"}; empty for an unknown group or key.
     */
    Optional<String> setting(String group, String key);

    /** The tenant's default receipt template, if one was set up (DOC-06). */
    Optional<ReceiptTemplateInfo> defaultReceiptTemplate();
}
