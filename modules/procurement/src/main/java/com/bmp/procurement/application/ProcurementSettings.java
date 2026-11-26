package com.bmp.procurement.application;

import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.tenant.api.TenantQueries;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** The tenant data procurement depends on: currency, rounding and document numbers (PRO-07). */
@Component
public class ProcurementSettings {

    private final TenantQueries tenants;
    private final NumberSequences numbers;

    public ProcurementSettings(TenantQueries tenants, @Qualifier("procurementNumbers") NumberSequences numbers) {
        this.tenants = tenants;
        this.numbers = numbers;
    }

    public String currency() {
        return tenants.currentTenant()
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null))
                .currency();
    }

    public RoundingPolicy rounding() {
        return RoundingPolicy.defaultPolicy();
    }

    /** Rule PRO-07: the next purchase order number, e.g. {@code PO-000001}. */
    public String nextOrderNumber() {
        return prefix("purchaseOrderPrefix", "PO-") + "%06d".formatted(numbers.next("PURCHASE_ORDER"));
    }

    /** Rule PRO-07: the next goods receipt number, e.g. {@code GRN-000001}. */
    public String nextReceiptNumber() {
        return prefix("goodsReceiptPrefix", "GRN-") + "%06d".formatted(numbers.next("GOODS_RECEIPT"));
    }

    private String prefix(String key, String fallback) {
        Optional<String> value = tenants.setting("numbering", key);
        return value.map(ProcurementSettings::unquoted).orElse(fallback);
    }

    /** Setting values are JSON text: {@code "PO-"}. */
    static String unquoted(String json) {
        String value = json.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
