package com.bmp.pricing.application;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The tenant data pricing depends on: currency, time zone and the {@code pricing} settings group. */
@Component
public class PricingSettings {

    private static final String GROUP = "pricing";

    private final TenantQueries tenants;

    public PricingSettings(TenantQueries tenants) {
        this.tenants = tenants;
    }

    public TenantSummary tenant() {
        return tenants.findTenant(TenantContext.require())
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
    }

    public ZoneId zone() {
        return ZoneId.of(tenant().timeZone());
    }

    /** PRC-05: derive unit prices from the base unit when no price exists. */
    public boolean derivePriceFromBase() {
        return flag("derivePriceFromBase");
    }

    /** Default for new price lists. */
    public boolean pricesTaxInclusive() {
        return flag("pricesTaxInclusive");
    }

    /** PRC-08: only owners may change prices. */
    public boolean priceChangesRequireOwner() {
        return flag("priceChangesRequireOwner");
    }

    private boolean flag(String key) {
        return tenants.setting(GROUP, key).map(Boolean::parseBoolean).orElse(false);
    }
}
