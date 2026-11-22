package com.bmp.pricing.application.query;

import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.RuleConditions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A price rule as the API shows it. */
public record PriceRuleView(
        UUID id,
        String code,
        String name,
        String kind,
        RuleConditions conditions,
        String effectType,
        BigDecimal effectValue,
        int priority,
        boolean stackable,
        @Nullable Instant validFrom,
        @Nullable Instant validTo,
        String status,
        long version,
        Instant createdAt) {

    public static PriceRuleView of(PriceRule rule) {
        return new PriceRuleView(
                rule.id(),
                rule.code(),
                rule.name(),
                rule.kind().name(),
                rule.conditions(),
                rule.effect().type().name(),
                rule.effect().value(),
                rule.priority(),
                rule.stackable(),
                rule.validFrom(),
                rule.validTo(),
                rule.status().name(),
                rule.version(),
                rule.createdAt());
    }
}
