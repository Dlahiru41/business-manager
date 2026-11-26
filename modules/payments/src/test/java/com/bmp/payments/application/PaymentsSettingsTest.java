package com.bmp.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bmp.tenant.api.TenantQueries;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentsSettingsTest {

    private final TenantQueries tenants = mock(TenantQueries.class);
    private final PaymentsSettings settings = new PaymentsSettings(tenants);

    @Test
    void denominationsAreTheTenantsOrTheRupeesNotesAndCoins() {
        when(tenants.setting("payments", "cashDenominations")).thenReturn(Optional.of("[\"5000\", \"0.50\", 20]"));
        assertThat(settings.cashDenominations())
                .extracting(BigDecimal::toPlainString)
                .containsExactly("5000", "0.50", "20");

        when(tenants.setting("payments", "cashDenominations")).thenReturn(Optional.of("[]"));
        assertThat(settings.cashDenominations()).isEqualTo(PaymentsSettings.LKR);
        when(tenants.setting("payments", "cashDenominations")).thenReturn(Optional.empty());
        assertThat(settings.cashDenominations()).hasSize(11);
    }

    @Test
    void theAlertAmountAndTheReferenceRuleComeFromTheSettingsOrTheirDefaults() {
        when(tenants.setting("payments", "drawerVarianceAlertAmount")).thenReturn(Optional.of("\"250.00\""));
        when(tenants.setting("sales", "tenderReferenceRequired")).thenReturn(Optional.of("false"));
        assertThat(settings.drawerVarianceAlertAmount()).isEqualByComparingTo("250.00");
        assertThat(settings.tenderReferenceRequired()).isFalse();

        when(tenants.setting("payments", "drawerVarianceAlertAmount")).thenReturn(Optional.empty());
        when(tenants.setting("sales", "tenderReferenceRequired")).thenReturn(Optional.empty());
        assertThat(settings.drawerVarianceAlertAmount()).isEqualByComparingTo("500.00");
        assertThat(settings.tenderReferenceRequired()).isTrue();
        assertThat(PaymentsSettings.unquoted("\"")).isEqualTo("\"");
        assertThat(PaymentsSettings.unquoted("\"x")).isEqualTo("\"x");
        assertThat(PaymentsSettings.unquoted("x\"")).isEqualTo("x\"");
    }
}
