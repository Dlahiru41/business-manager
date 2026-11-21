package com.bmp.tenant.application;

import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.spi.BusinessActivityProbe;
import com.bmp.tenant.domain.port.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Default for TEN-02 until sales knows about bills: a tenant older than one day is assumed to have business documents.
 * Conservative: it locks currency and time zone early rather than too late.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantAgeActivityProbe implements BusinessActivityProbe {

    static final Duration GRACE_PERIOD = Duration.ofDays(1);

    private final TenantRepository tenants;
    private final Clock clock;

    public TenantAgeActivityProbe(TenantRepository tenants, Clock clock) {
        this.tenants = tenants;
        this.clock = clock;
    }

    @Override
    public boolean hasBusinessDocuments(TenantId tenant) {
        return tenants.findById(tenant.value())
                .map(found -> found.createdBefore(clock.instant().minus(GRACE_PERIOD)))
                .orElse(true);
    }
}
