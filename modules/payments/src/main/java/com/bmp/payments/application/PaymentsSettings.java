package com.bmp.payments.application;

import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.time.BusinessDate;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** The tenant settings payments works with (domain-model.md §4), read per use. */
@Component
public class PaymentsSettings {

    /** Sri Lankan rupee notes and coins, the default of {@code payments.cashDenominations}. */
    static final List<BigDecimal> LKR =
            List.of("5000", "2000", "1000", "500", "100", "50", "20", "10", "5", "2", "1").stream()
                    .map(BigDecimal::new)
                    .toList();

    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:[.][0-9]+)?");

    private final TenantQueries tenants;

    public PaymentsSettings(TenantQueries tenants) {
        this.tenants = tenants;
    }

    public TenantSummary tenant() {
        return tenants.currentTenant()
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
    }

    public String currency() {
        return tenant().currency();
    }

    /** The tenant-local date with the cut-off hour (SAL-13), for daily totals (PAY-06). */
    public LocalDate businessDate(Instant at) {
        TenantSummary t = tenant();
        return BusinessDate.of(at, ZoneId.of(t.timeZone()), t.businessDayCutoffHour())
                .date();
    }

    /** PAY-04: a variance above this alerts. */
    public BigDecimal drawerVarianceAlertAmount() {
        return new BigDecimal(unquoted(
                tenants.setting("payments", "drawerVarianceAlertAmount").orElse("500.00")));
    }

    /** PAY-04: the notes and coins a drawer is counted in. */
    public List<BigDecimal> cashDenominations() {
        return tenants.setting("payments", "cashDenominations")
                .map(PaymentsSettings::decimals)
                .filter(list -> !list.isEmpty())
                .orElse(LKR);
    }

    /** SAL-06, PAY-02: whether CARD and BANK_TRANSFER need a reference. */
    public boolean tenderReferenceRequired() {
        return Boolean.parseBoolean(
                unquoted(tenants.setting("sales", "tenderReferenceRequired").orElse("true")));
    }

    /** A JSON array of decimals as text: {@code ["5000", "1000"]} or {@code [5000, 1000]}. */
    static List<BigDecimal> decimals(String json) {
        List<BigDecimal> values = new ArrayList<>();
        Matcher m = DECIMAL.matcher(json);
        while (m.find()) {
            values.add(new BigDecimal(m.group()));
        }
        return values;
    }

    static String unquoted(String json) {
        String value = json.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
