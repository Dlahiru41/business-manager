package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.api.PriceResolutionApi;
import com.bmp.pricing.adapter.in.web.generated.model.ResolvePricesRequest;
import com.bmp.pricing.adapter.in.web.generated.model.ResolvedPrices;
import com.bmp.pricing.application.query.ResolvePricesService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prices a basket (PRC-04..PRC-07, PRC-10). Resolution has no side effects, so it is not {@code @Idempotent}: the
 * Idempotency-Key every POST carries is accepted and nothing is stored.
 */
@RestController
class PriceResolutionController implements PriceResolutionApi {

    private final ResolvePricesService resolver;
    private final PricingWebMapper mapper;

    PriceResolutionController(ResolvePricesService resolver, PricingWebMapper mapper) {
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<ResolvedPrices> resolvePrices(UUID idempotencyKey, ResolvePricesRequest request) {
        return ResponseEntity.ok(mapper.toDto(resolver.resolve(PricingRequests.basket(request))));
    }
}
