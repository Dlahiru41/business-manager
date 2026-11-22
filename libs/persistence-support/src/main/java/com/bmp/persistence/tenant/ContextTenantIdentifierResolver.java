package com.bmp.persistence.tenant;

import com.bmp.shared.context.TenantContext;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Feeds Hibernate's {@code @TenantId} discriminator from {@link TenantContext}.
 *
 * <p>Without a tenant it returns the nil UUID, which matches no tenant, instead of failing: infrastructure
 * transactions on platform tables (event publications, ShedLock) legitimately run without one. Isolation does not
 * depend on this sentinel: the row-level security policies raise {@code TENANT_CONTEXT_MISSING} for any read or
 * write of tenant-owned rows when no tenant is set (defence in depth, 06-persistence-and-data.md §4).
 */
public class ContextTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    /** Tenant id used when no tenant is bound; never a real tenant. */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current().map(tenant -> tenant.value()).orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
