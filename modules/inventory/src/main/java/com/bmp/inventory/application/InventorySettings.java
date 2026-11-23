package com.bmp.inventory.application;

import com.bmp.inventory.domain.policy.CostingMethod;
import com.bmp.inventory.domain.policy.CostingPolicy;
import com.bmp.inventory.domain.policy.NegativeStockPolicy;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The tenant data inventory depends on: currency and the {@code inventory} settings group (01 §7, §8). */
@Component
public class InventorySettings {

    private static final String GROUP = "inventory";

    private final TenantQueries tenants;

    public InventorySettings(TenantQueries tenants) {
        this.tenants = tenants;
    }

    /** The current tenant, read in the caller's transaction. */
    public TenantSummary tenant() {
        return tenants.currentTenant()
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
    }

    public RoundingPolicy rounding() {
        return RoundingPolicy.defaultPolicy();
    }

    /** Rules INV-03, INV-04: moving average unless the tenant chose FIFO. */
    public CostingPolicy costing() {
        CostingMethod method =
                setting("costingMethod").map(CostingMethod::valueOf).orElse(CostingMethod.MOVING_AVERAGE);
        return method.policy(rounding());
    }

    /** Rule INV-08: allow and flag unless the tenant blocks negative stock. */
    public NegativeStockPolicy negativeStockPolicy() {
        return setting("negativeStockPolicy")
                .map(NegativeStockPolicy::valueOf)
                .orElse(NegativeStockPolicy.ALLOW_AND_FLAG);
    }

    private java.util.Optional<String> setting(String key) {
        return tenants.setting(GROUP, key).map(InventorySettings::unquoted);
    }

    /** Setting values are JSON text: {@code "FIFO"}. */
    static String unquoted(String json) {
        String value = json.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
