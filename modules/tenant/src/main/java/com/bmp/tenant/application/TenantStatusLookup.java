package com.bmp.tenant.application;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.domain.port.TenantRepository;
import com.bmp.web.security.TenantStatusProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant status for request authorization ({@link TenantStatusProvider}), cached for 60 seconds per tenant and evicted
 * on this instance when the status changes; other instances converge within the cache lifetime.
 */
@Component
public class TenantStatusLookup implements TenantStatusProvider {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long MAX_TENANTS = 10_000;

    private final TenantRepository tenants;
    private final TransactionTemplate readOnly;
    private final Cache<TenantId, TenantStatus> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(MAX_TENANTS)
            .build();

    public TenantStatusLookup(TenantRepository tenants, PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        // Its own transaction: the tenant is fixed per transaction, so a lookup for another tenant must not join one.
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public TenantStatus statusOf(TenantId tenant) {
        return cache.get(tenant, this::load);
    }

    public void evict(TenantId tenant) {
        cache.invalidate(tenant);
    }

    private TenantStatus load(TenantId tenant) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant,
                () -> readOnly.execute(tx -> tenants.findById(tenant.value())
                        .map(found -> TenantStatus.valueOf(found.status().name()))
                        .orElse(TenantStatus.UNKNOWN))));
    }
}
