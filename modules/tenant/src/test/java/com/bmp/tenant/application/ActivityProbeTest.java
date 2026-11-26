package com.bmp.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.tenant.api.spi.ActivityEvidence;
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

class ActivityProbeTest {

    private final MutableClock clock = MutableClock.at("2026-11-21T09:00:00Z");
    private final Map<UUID, Tenant> stored = new HashMap<>();
    private final TenantRepository tenants = new TenantRepository() {
        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public Optional<Tenant> lockById(UUID id) {
            return findById(id);
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

        assertThat(probe.evidenceOf(tenant.id())).isEqualTo(ActivityEvidence.NONE);
        clock.advance(Duration.ofHours(25));
        assertThat(probe.evidenceOf(tenant.id())).isEqualTo(ActivityEvidence.ASSUMED);
        assertThat(probe.evidenceOf(TenantId.of(UUID.randomUUID())))
                .as("unknown tenants are treated conservatively")
                .isEqualTo(ActivityEvidence.ASSUMED);
    }

    @Test
    void theStrongestAnswerOfAllProbesCounts() {
        TenantAgeActivityProbe fallback = new TenantAgeActivityProbe(tenants, clock);
        BusinessActivityProbe hasBills = tenant -> ActivityEvidence.CONFIRMED;
        BusinessActivityProbe noBills = tenant -> ActivityEvidence.NONE;
        Tenant tenant = storedTenant();

        assertThat(new BusinessActivityProbes(List.of(fallback, hasBills)).evidenceOf(tenant.id()))
                .isEqualTo(ActivityEvidence.CONFIRMED);
        assertThat(new BusinessActivityProbes(List.of(noBills, fallback)).evidenceOf(tenant.id()))
                .as("a young tenant without bills")
                .isEqualTo(ActivityEvidence.NONE);
        clock.advance(Duration.ofHours(25));
        assertThat(new BusinessActivityProbes(List.of(noBills, fallback)).evidenceOf(tenant.id()))
                .as("no bills says nothing about the tenant's other documents")
                .isEqualTo(ActivityEvidence.ASSUMED);
        assertThat(new BusinessActivityProbes(List.of(hasBills, fallback)).evidenceOf(tenant.id()))
                .isEqualTo(ActivityEvidence.CONFIRMED);
        assertThatThrownBy(() -> new BusinessActivityProbes(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
