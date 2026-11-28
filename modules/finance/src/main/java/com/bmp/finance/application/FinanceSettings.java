package com.bmp.finance.application;

import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.time.BusinessDate;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The tenant's finance settings and business dates (FIN-05, FIN-06, FIN-08, ADR-032 §6). */
@Component
public class FinanceSettings {

    private final TenantQueries tenants;
    private final Clock clock;

    public FinanceSettings(TenantQueries tenants, Clock clock) {
        this.tenants = tenants;
        this.clock = clock;
    }

    public TenantSummary tenant() {
        return tenants.currentTenant()
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
    }

    public String currency() {
        return tenant().currency();
    }

    /** The business date of a moment: tenant time zone and cut-off hour. */
    public LocalDate businessDate(Instant at) {
        TenantSummary t = tenant();
        return BusinessDate.of(at, ZoneId.of(t.timeZone()), t.businessDayCutoffHour())
                .date();
    }

    public LocalDate today() {
        return businessDate(clock.instant());
    }

    /** Rule FIN-06: the first month of the fiscal year (1–12). */
    public int fiscalYearStartMonth() {
        return Integer.parseInt(
                tenants.setting("finance", "fiscalYearStartMonth").orElse("1"));
    }

    /** Rule FIN-08: days past due before a customer is reported overdue. */
    public int overdueGraceDays() {
        return Integer.parseInt(tenants.setting("finance", "overdueGraceDays").orElse("7"));
    }

    /** ADR-032 §6: whether input tax on supplier invoices is reclaimed. */
    public boolean inputTaxReclaimable() {
        return Boolean.parseBoolean(
                tenants.setting("finance", "inputTaxReclaimable").orElse("false"));
    }
}
