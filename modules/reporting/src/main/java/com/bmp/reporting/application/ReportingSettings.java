package com.bmp.reporting.application;

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

/** The tenant's business dates and hours for the read models. */
@Component
public class ReportingSettings {

    private final TenantQueries tenants;
    private final Clock clock;

    public ReportingSettings(TenantQueries tenants, Clock clock) {
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

    public ZoneId zone() {
        return ZoneId.of(tenant().timeZone());
    }

    public LocalDate businessDate(Instant at) {
        TenantSummary t = tenant();
        return BusinessDate.of(at, ZoneId.of(t.timeZone()), t.businessDayCutoffHour())
                .date();
    }

    public LocalDate today() {
        return businessDate(clock.instant());
    }

    public int hour(Instant at) {
        return at.atZone(zone()).getHour();
    }

    public Instant now() {
        return clock.instant();
    }
}
