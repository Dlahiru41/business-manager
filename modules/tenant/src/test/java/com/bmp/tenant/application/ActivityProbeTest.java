package com.bmp.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.tenant.api.spi.BusinessActivityProbe;
import com.bmp.tenant.domain.model.Tenant;
import com.bmp.tenant.domain.port.TenantRepository;
import com.bmp.test.time.MutableClock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

class ActivityProbeTest {

    private final MutableClock clock = MutableClock.at("2026-11-21T09:00:00Z");
    private final Map<UUID, Tenant> stored = new HashMap<>();
    private final TenantRepository tenants = new TenantRepository() {
        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public Tenant save(Tenant tenant) {
            stored.put(tenant.id().value(), tenant);
            return tenant;
        }

        @Override
        public Tenant saveAndFlush(Tenant tenant) {
            return save(tenant);
        }
    };

    private Tenant storedTenant() {
        Tenant tenant = Tenant.create(
                TenantId.of(UUID.randomUUID()),
                new Tenant.TenantProfile("Shop", null, "RETAIL"),
                new Tenant.Regional(CurrencyCode.LKR, ZoneId.of("Asia/Colombo"), 0),
                null,
                null,
                () -> new EventStamp(UUID.randomUUID(), clock.instant(), EventMetadata.empty()));
        tenant.recordCreation(clock.instant(), null);
        return tenants.save(tenant);
    }

    @Test
    void ten02DefaultProbeAssumesDocumentsOnceATenantIsOlderThanADay() {
        TenantAgeActivityProbe probe = new TenantAgeActivityProbe(tenants, clock);
        Tenant tenant = storedTenant();

        assertThat(probe.hasBusinessDocuments(tenant.id())).isFalse();
        clock.advance(Duration.ofHours(25));
        assertThat(probe.hasBusinessDocuments(tenant.id())).isTrue();
        assertThat(probe.hasBusinessDocuments(TenantId.of(UUID.randomUUID())))
                .as("unknown tenants are treated conservatively")
                .isTrue();
    }

    @Test
    void aDownstreamProbeWithHigherPrecedenceWins() {
        @Order(1)
        class SalesProbe implements BusinessActivityProbe {
            @Override
            public boolean hasBusinessDocuments(TenantId tenant) {
                return false;
            }
        }
        TenantAgeActivityProbe fallback = new TenantAgeActivityProbe(tenants, clock);

        BusinessActivityProbes probes = new BusinessActivityProbes(List.of(fallback, new SalesProbe()));

        assertThat(probes.hasBusinessDocuments(TenantId.of(UUID.randomUUID()))).isFalse();
        assertThat(new BusinessActivityProbes(List.of(fallback)).hasBusinessDocuments(TenantId.of(UUID.randomUUID())))
                .isTrue();
    }
}
