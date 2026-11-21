package com.bmp.tenant.domain.port;

import com.bmp.tenant.domain.model.Tenant;
import java.util.Optional;
import java.util.UUID;

/** Tenants. Row-level security limits every query to the tenant bound to the transaction. */
public interface TenantRepository {

    Optional<Tenant> findById(UUID id);

    Tenant save(Tenant tenant);

    /** Saves and flushes, so the returned entity carries its new version. */
    Tenant saveAndFlush(Tenant tenant);
}
