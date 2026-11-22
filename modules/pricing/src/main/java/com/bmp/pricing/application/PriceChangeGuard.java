package com.bmp.pricing.application;

import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.security.SystemRole;
import com.bmp.web.security.AccessPolicy;
import com.bmp.web.security.BmpPrincipal;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Rule PRC-08: changing prices needs {@code pricing:write} (checked on the endpoint); a tenant setting may restrict
 * it further to owners. Platform administrators acting for the tenant are let through (audited).
 */
@Component
public class PriceChangeGuard {

    private final PricingSettings settings;

    public PriceChangeGuard(PricingSettings settings) {
        this.settings = settings;
    }

    public void requireAllowed() {
        if (!settings.priceChangesRequireOwner()) {
            return;
        }
        Optional<BmpPrincipal> caller = AccessPolicy.currentPrincipal();
        boolean allowed = caller.map(p -> p.isPlatformAdmin() || p.roles().contains(SystemRole.OWNER.code()))
                .orElse(false);
        if (!allowed) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.PRICE_CHANGE_RESTRICTED,
                    "Only owners may change prices in this business",
                    Map.of("setting", "pricing.priceChangesRequireOwner"));
        }
    }
}
