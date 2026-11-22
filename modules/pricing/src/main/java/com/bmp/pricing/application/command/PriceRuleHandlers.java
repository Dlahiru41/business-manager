package com.bmp.pricing.application.command;

import com.bmp.pricing.application.PriceChangeGuard;
import com.bmp.pricing.application.PricingLoader;
import com.bmp.pricing.application.query.PriceRuleView;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.PricingStatus;
import com.bmp.pricing.domain.model.RuleConditions;
import com.bmp.pricing.domain.model.RuleEffect;
import com.bmp.pricing.domain.model.RuleKind;
import com.bmp.pricing.domain.port.PriceRuleRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and changes price rules (PRC-06, PRC-08). */
@Service
public class PriceRuleHandlers {

    private final PriceRuleRepository rules;
    private final PricingLoader loader;
    private final PriceChangeGuard guard;
    private final IdGenerator ids;

    public PriceRuleHandlers(PriceRuleRepository rules, PricingLoader loader, PriceChangeGuard guard, IdGenerator ids) {
        this.rules = rules;
        this.loader = loader;
        this.guard = guard;
        this.ids = ids;
    }

    public record CreateRule(
            String code,
            String name,
            RuleKind kind,
            RuleConditions conditions,
            RuleEffect effect,
            int priority,
            boolean stackable,
            @Nullable Instant validFrom,
            @Nullable Instant validTo) {}

    /** PATCH semantics: {@code null} fields stay as they are; a given validity end or start replaces that end. */
    public record UpdateRule(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable RuleConditions conditions,
            @Nullable RuleEffect effect,
            @Nullable Integer priority,
            @Nullable Boolean stackable,
            @Nullable Instant validFrom,
            @Nullable Instant validTo,
            @Nullable PricingStatus status) {}

    @Transactional
    @Observed(name = "bmp.pricing.create-price-rule")
    public PriceRuleView create(CreateRule command) {
        guard.requireAllowed();
        PriceRule rule = PriceRule.create(
                ids.newUuid(),
                TenantContext.require(),
                command.code(),
                command.name(),
                command.kind(),
                command.conditions(),
                command.effect(),
                command.priority(),
                command.stackable(),
                command.validFrom(),
                command.validTo());
        return PriceRuleView.of(rules.saveAndFlush(rule));
    }

    @Transactional
    @Observed(name = "bmp.pricing.update-price-rule")
    public PriceRuleView update(UpdateRule command) {
        guard.requireAllowed();
        PriceRule rule = loader.rule(command.id());
        IfMatch.check(command.expectedVersion(), rule.version());
        if (command.name() != null) {
            rule.rename(command.name());
        }
        if (command.conditions() != null) {
            rule.changeConditions(command.conditions());
        }
        if (command.effect() != null) {
            rule.changeEffect(command.effect());
        }
        if (command.priority() != null || command.stackable() != null) {
            rule.reprioritise(
                    command.priority() != null ? command.priority() : rule.priority(),
                    command.stackable() != null ? command.stackable() : rule.stackable());
        }
        if (command.validFrom() != null || command.validTo() != null) {
            rule.validity(
                    command.validFrom() != null ? command.validFrom() : rule.validFrom(),
                    command.validTo() != null ? command.validTo() : rule.validTo());
        }
        if (command.status() == PricingStatus.INACTIVE) {
            rule.deactivate();
        } else if (command.status() == PricingStatus.ACTIVE) {
            rule.activate();
        }
        return PriceRuleView.of(rules.saveAndFlush(rule));
    }
}
