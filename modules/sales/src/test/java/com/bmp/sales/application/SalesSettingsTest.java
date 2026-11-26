package com.bmp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesSettingsTest {

    @Test
    void settingValuesLoseTheirJsonQuotesOnlyWhenQuotedOnBothSides() {
        assertThat(SalesSettings.unquoted(" \"5.00\" ")).isEqualTo("5.00");
        assertThat(SalesSettings.unquoted("7")).isEqualTo("7");
        assertThat(SalesSettings.unquoted("true")).isEqualTo("true");
        assertThat(SalesSettings.unquoted("\""))
                .as("a lone quote is not a quoted value")
                .isEqualTo("\"");
        assertThat(SalesSettings.unquoted("\"open")).isEqualTo("\"open");
        assertThat(SalesSettings.unquoted("open\"")).isEqualTo("open\"");
        assertThat(SalesSettings.unquoted("\"\"")).isEmpty();
    }

    @Test
    void sal13TheBusinessDateIsTheCompletionInTheTenantsTimeZoneBeforeItsCutOffHour() {
        TenantQueries tenants = mock(TenantQueries.class);
        when(tenants.currentTenant())
                .thenReturn(Optional.of(new TenantSummary(
                        TenantId.of(UUID.randomUUID()), "Lanka Mart", "LKR", "Asia/Colombo", 4, "ACTIVE")));
        SalesSettings settings = new SalesSettings(tenants);

        assertThat(settings.businessDate(Instant.parse("2026-11-25T18:29:59Z")))
                .as("23:59:59 in Colombo")
                .isEqualTo(LocalDate.parse("2026-11-25"));
        assertThat(settings.businessDate(Instant.parse("2026-11-25T22:29:59Z")))
                .as("03:59:59 the next morning, before the 04:00 cut-off")
                .isEqualTo(LocalDate.parse("2026-11-25"));
        assertThat(settings.businessDate(Instant.parse("2026-11-25T22:30:00Z")))
                .as("04:00: the next business day")
                .isEqualTo(LocalDate.parse("2026-11-26"));
    }
}
