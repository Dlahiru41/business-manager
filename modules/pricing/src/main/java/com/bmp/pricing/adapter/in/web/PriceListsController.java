package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.api.PriceListsApi;
import com.bmp.pricing.adapter.in.web.generated.model.CreatePriceListRequest;
import com.bmp.pricing.adapter.in.web.generated.model.ListChannel;
import com.bmp.pricing.adapter.in.web.generated.model.PriceList;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListPage;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListPagePage;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListVersion;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListVersionPage;
import com.bmp.pricing.adapter.in.web.generated.model.Status;
import com.bmp.pricing.adapter.in.web.generated.model.UpdatePriceListRequest;
import com.bmp.pricing.application.command.PriceListHandlers;
import com.bmp.pricing.application.query.PriceListVersionView;
import com.bmp.pricing.application.query.PriceListView;
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

/** Price lists and their versions (PRC-01, PRC-03). */
@RestController
class PriceListsController implements PriceListsApi {

    private final PricingQueryService queries;
    private final PriceListHandlers handlers;
    private final PricingWebMapper mapper;

    PriceListsController(PricingQueryService queries, PriceListHandlers handlers, PricingWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<PriceListPage> listPriceLists(
            @Nullable ListChannel channel, @Nullable Status status, @Nullable Integer limit, @Nullable String cursor) {
        PageResponse<PriceListView> page = queries.priceLists(
                channel != null ? com.bmp.pricing.domain.model.ListChannel.valueOf(channel.getValue()) : null,
                PricingRequests.status(status),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PriceListPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceList> createPriceList(UUID idempotencyKey, CreatePriceListRequest request) {
        PriceListView created = handlers.create(new PriceListHandlers.CreatePriceList(
                request.getCode(),
                request.getName(),
                com.bmp.pricing.domain.model.ListChannel.valueOf(
                        request.getChannel().getValue()),
                request.getCustomerGroupId(),
                request.getCustomerId(),
                request.getTaxInclusive(),
                request.getPriority()));
        return ResponseEntity.created(URI.create("/api/v1/price-lists/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<PriceList> getPriceList(UUID priceListId) {
        PriceListView view = queries.priceList(priceListId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<PriceList> updatePriceList(
            UUID priceListId, UpdatePriceListRequest request, @Nullable String ifMatch) {
        PriceListView view = handlers.update(new PriceListHandlers.UpdatePriceList(
                priceListId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getPriority(),
                PricingRequests.status(request.getStatus())));
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<PriceListVersionPage> listPriceListVersions(
            UUID priceListId, @Nullable Integer limit, @Nullable String cursor) {
        PageResponse<PriceListVersionView> page = queries.versions(priceListId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PriceListVersionPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(page(page)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:read')")
    public ResponseEntity<PriceListVersion> getPriceListVersion(UUID priceListId, Integer version) {
        return ResponseEntity.ok(mapper.toDto(queries.version(priceListId, version)));
    }

    /** The page envelope of every pricing list. */
    static PriceListPagePage page(PageResponse<?> page) {
        return new PriceListPagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }
}
