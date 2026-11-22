package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.api.PriceRulesApi;
import com.bmp.pricing.adapter.in.web.generated.model.CreatePriceRuleRequest;
import com.bmp.pricing.adapter.in.web.generated.model.PriceRule;
import com.bmp.pricing.adapter.in.web.generated.model.PriceRulePage;
import com.bmp.pricing.adapter.in.web.generated.model.RuleKind;
import com.bmp.pricing.adapter.in.web.generated.model.Status;
import com.bmp.pricing.adapter.in.web.generated.model.UpdatePriceRuleRequest;
import com.bmp.pricing.application.command.PriceRuleHandlers;
import com.bmp.pricing.application.query.PriceRuleView;
import com.bmp.pricing.application.query.PricingQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Price rules (PRC-06). */
@RestController
class PriceRulesController implements PriceRulesApi {

    private final PricingQueryService queries;
    private final PriceRuleHandlers handlers;
    private final PricingWebMapper mapper;

    PriceRulesController(PricingQueryService queries, PriceRuleHandlers handlers, PricingWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceRulePage> listPriceRules(
            @Nullable RuleKind kind, @Nullable Status status, @Nullable Integer limit, @Nullable String cursor) {
        PageResponse<PriceRuleView> page = queries.rules(
                kind != null ? com.bmp.pricing.domain.model.RuleKind.valueOf(kind.getValue()) : null,
                PricingRequests.status(status),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PriceRulePage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(PriceListsController.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceRule> createPriceRule(UUID idempotencyKey, CreatePriceRuleRequest request) {
        PriceRuleView created = handlers.create(new PriceRuleHandlers.CreateRule(
                request.getCode(),
                request.getName(),
                com.bmp.pricing.domain.model.RuleKind.valueOf(request.getKind().getValue()),
                PricingRequests.conditions(request.getConditions()),
                PricingRequests.effect(request.getEffect()),
                request.getPriority() != null ? request.getPriority() : 100,
                Boolean.TRUE.equals(request.getStackable()),
                PricingRequests.instant(request.getValidFrom()),
                PricingRequests.instant(request.getValidTo())));
        return ResponseEntity.created(URI.create("/api/v1/price-rules/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceRule> getPriceRule(UUID ruleId) {
        PriceRuleView view = queries.rule(ruleId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceRule> updatePriceRule(
            UUID ruleId, UpdatePriceRuleRequest request, @Nullable String ifMatch) {
        PriceRuleView view = handlers.update(new PriceRuleHandlers.UpdateRule(
                ruleId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getConditions() != null ? PricingRequests.conditions(request.getConditions()) : null,
                request.getEffect() != null ? PricingRequests.effect(request.getEffect()) : null,
                request.getPriority(),
                request.getStackable(),
                PricingRequests.instant(request.getValidFrom()),
                PricingRequests.instant(request.getValidTo()),
                PricingRequests.status(request.getStatus())));
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
