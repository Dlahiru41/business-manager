package com.bmp.sales.application;

import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.shared.time.BusinessDate;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The tenant settings sales works with (domain-model.md §4), read per use. */
@Component
public class SalesSettings {

    private final TenantQueries tenants;

    public SalesSettings(TenantQueries tenants) {
        this.tenants = tenants;
    }

    public TenantSummary tenant() {
        return tenants.currentTenant()
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
    }

    public String currency() {
        return tenant().currency();
    }

    public RoundingPolicy rounding() {
        return RoundingPolicy.defaultPolicy();
    }

    /** Rule SAL-13: the tenant-local date with the cut-off hour. */
    public LocalDate businessDate(Instant at) {
        TenantSummary t = tenant();
        return BusinessDate.of(at, ZoneId.of(t.timeZone()), t.businessDayCutoffHour())
                .date();
    }

    public BigDecimal maxCashierDiscountPct() {
        return new BigDecimal(text("sales", "maxCashierDiscountPct", "5.00"));
    }

    public int returnWindowDays() {
        return Integer.parseInt(text("sales", "returnWindowDays", "7"));
    }

    public boolean voidRequiresManager() {
        return Boolean.parseBoolean(text("sales", "voidRequiresManager", "true"));
    }

    public boolean allowCreditSales() {
        return Boolean.parseBoolean(text("sales", "allowCreditSales", "false"));
    }

    public int offlineMaxHours() {
        return Integer.parseInt(text("sales", "offlineMaxHours", "72"));
    }

    public boolean wholesaleRequiresCustomer() {
        return Boolean.parseBoolean(text("sales", "wholesaleRequiresCustomer", "false"));
    }

    public boolean tenderReferenceRequired() {
        return Boolean.parseBoolean(text("sales", "tenderReferenceRequired", "true"));
    }

    public String creditNotePrefix() {
        return text("numbering", "creditNotePrefix", "CN-");
    }

    private String text(String group, String key, String fallback) {
        return tenants.setting(group, key).map(SalesSettings::unquoted).orElse(fallback);
    }

    /** Setting values are JSON text: {@code "5.00"}, {@code 7}, {@code true}. */
    static String unquoted(String json) {
        String value = json.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
