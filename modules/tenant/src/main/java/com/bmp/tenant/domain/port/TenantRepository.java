package com.bmp.tenant.domain.port;

import com.bmp.tenant.domain.model.Tenant;
import java.util.Optional;
import java.util.UUID;

/** Tenants. Row-level security limits every query to the tenant bound to the transaction. */
public interface TenantRepository {

    Optional<Tenant> findById(UUID id);

    /**
     * Reads the tenant with a row lock held until the transaction ends: serializes changes that check a rule over
     * several rows of the tenant (TEN-01 last active location, the single default receipt template).
     */
    Optional<Tenant> lockById(UUID id);

    Tenant save(Tenant tenant);

    /** Saves and flushes, so the returned entity carries its new version. */
    Tenant saveAndFlush(Tenant tenant);
}
