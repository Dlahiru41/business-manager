package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.api.PricesApi;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListSnapshot;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListVersion;
import com.bmp.pricing.adapter.in.web.generated.model.PricePage;
import com.bmp.pricing.adapter.in.web.generated.model.UpsertPricesRequest;
import com.bmp.pricing.application.command.UpsertPricesHandler;
import com.bmp.pricing.application.query.PriceListVersionView;
import com.bmp.pricing.application.query.PriceSnapshotView;
import com.bmp.pricing.application.query.PriceView;
import com.bmp.pricing.application.query.PricingQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.OffsetDateTime;
import java.util.OptionalLong;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Prices of a list: pages, bulk upsert (PRC-02, PRC-03) and till snapshots (PRC-09); the ETag is the price version. */
@RestController
class PricesController implements PricesApi {

    private final PricingQueryService queries;
    private final UpsertPricesHandler upsert;
    private final PricingWebMapper mapper;

    PricesController(PricingQueryService queries, UpsertPricesHandler upsert, PricingWebMapper mapper) {
        this.queries = queries;
        this.upsert = upsert;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<PricePage> listPrices(
            UUID priceListId,
            @Nullable UUID variantId,
            @Nullable UUID unitId,
            @Nullable OffsetDateTime at,
            @Nullable Integer limit,
            @Nullable String cursor) {
        PageResponse<PriceView> page = queries.prices(
                priceListId, variantId, unitId, PricingRequests.instant(at), PageQuery.of(limit, cursor));
        return ResponseEntity.ok()
                .eTag(Etags.of(queries.priceVersion(priceListId)))
                .body(new PricePage()
                        .items(page.items().stream().map(mapper::toDto).toList())
                        .page(PriceListsController.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceListVersion> upsertPrices(
            UUID priceListId, UUID idempotencyKey, UpsertPricesRequest request, @Nullable String ifMatch) {
        PriceListVersionView version = upsert.handle(new UpsertPricesHandler.UpsertPrices(
                priceListId, IfMatch.expectedVersion(ifMatch), request.getNote(), PricingRequests.prices(request)));
        return ResponseEntity.ok().eTag(Etags.of(version.version())).body(mapper.toDto(version));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<PriceListSnapshot> getPriceListSnapshot(
            UUID priceListId, @Nullable Integer sinceVersion, @Nullable String ifNoneMatch) {
        OptionalLong known = Etags.parse(ifNoneMatch);
        int version = queries.priceVersion(priceListId);
        if (known.isPresent() && known.getAsLong() == version) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(Etags.of(version))
                    .build();
        }
        PriceSnapshotView snapshot = queries.snapshot(priceListId, sinceVersion);
        return ResponseEntity.ok().eTag(Etags.of(snapshot.version())).body(mapper.toDto(snapshot));
    }
}
