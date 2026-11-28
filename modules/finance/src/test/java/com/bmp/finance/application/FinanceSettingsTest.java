package com.bmp.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.ReceiptTemplateInfo;
import com.bmp.tenant.api.dto.TenantSummary;
import com.bmp.test.time.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FinanceSettingsTest {

    private final Map<String, String> values = new HashMap<>();
    private TenantSummary tenant;

    private final TenantQueries queries = new TenantQueries() {
        @Override
        public Optional<TenantSummary> findTenant(TenantId id) {
            return Optional.ofNullable(tenant);
        }

        @Override
        public Optional<TenantSummary> currentTenant() {
            return Optional.ofNullable(tenant);
        }

        @Override
        public boolean isActiveLocation(LocationId location) {
            return true;
        }

        @Override
        public Optional<String> setting(String group, String key) {
            return Optional.ofNullable(values.get(group + "." + key));
        }

        @Override
        public Optional<ReceiptTemplateInfo> defaultReceiptTemplate() {
            return Optional.empty();
        }
    };

    private final MutableClock clock = MutableClock.at("2026-11-25T20:00:00Z");
    private final FinanceSettings settings = new FinanceSettings(queries, clock);

    @Test
    void fin05BusinessDatesFollowTheTenantZoneAndCutOff() {
        tenant = summary(4);

        assertThat(settings.currency()).isEqualTo("LKR");
        // 20:00 UTC is 01:30 the next day in Colombo, before the 04:00 cut-off: still the 25th.
        assertThat(settings.today()).isEqualTo(LocalDate.parse("2026-11-25"));
        assertThat(settings.businessDate(Instant.parse("2026-11-25T23:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-26"));
    }

    @Test
    void theFinanceSettingsHaveTheirDocumentedDefaultsAndTakeTheTenantsValues() {
        tenant = summary(0);
        assertThat(settings.fiscalYearStartMonth()).isEqualTo(1);
        assertThat(settings.overdueGraceDays()).isEqualTo(7);
        assertThat(settings.inputTaxReclaimable()).isFalse();

        values.put("finance.fiscalYearStartMonth", "4");
        values.put("finance.overdueGraceDays", "10");
        values.put("finance.inputTaxReclaimable", "true");
        assertThat(settings.fiscalYearStartMonth()).isEqualTo(4);
        assertThat(settings.overdueGraceDays()).isEqualTo(10);
        assertThat(settings.inputTaxReclaimable()).isTrue();
    }

    @Test
    void withoutATenantThereAreNoSettings() {
        tenant = null;
        assertThatThrownBy(settings::currency).isInstanceOf(TechnicalException.class);
    }

    private static TenantSummary summary(int cutoff) {
        return new TenantSummary(
                TenantId.of(java.util.UUID.randomUUID()), "Shop", "LKR", "Asia/Colombo", cutoff, "ACTIVE");
    }
}
