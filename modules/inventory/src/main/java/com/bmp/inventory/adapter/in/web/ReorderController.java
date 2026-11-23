package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.api.ReorderApi;
import com.bmp.inventory.adapter.in.web.generated.model.ReorderItem;
import com.bmp.inventory.adapter.in.web.generated.model.ReorderList;
import com.bmp.inventory.adapter.in.web.generated.model.ReorderPolicy;
import com.bmp.inventory.adapter.in.web.generated.model.ReorderPolicyRequest;
import com.bmp.inventory.application.command.ReorderPolicyHandler;
import com.bmp.inventory.application.query.StockQueryService;
import com.bmp.web.etag.Etags;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** The reorder list and reorder levels (INV-13, PRO-06). */
@RestController
class ReorderController implements ReorderApi {

    private final StockQueryService queries;
    private final ReorderPolicyHandler policies;
    private final InventoryWebMapper mapper;

    ReorderController(StockQueryService queries, ReorderPolicyHandler policies, InventoryWebMapper mapper) {
        this.queries = queries;
        this.policies = policies;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:read')")
    public ResponseEntity<ReorderList> getReorderList(@Nullable UUID locationId) {
        return ResponseEntity.ok(new ReorderList()
                .items(queries.reorderList(locationId).stream()
                        .map(r -> new ReorderItem()
                                .variantId(r.variantId())
                                .sku(r.sku())
                                .locationId(r.locationId())
                                .available(mapper.decimal(r.available()))
                                .reorderLevel(mapper.decimal(r.reorderLevel()))
                                .reorderQty(mapper.decimal(r.reorderQty()))
                                .preferredSupplierId(r.preferredSupplierId()))
                        .toList()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:write')")
    public ResponseEntity<ReorderPolicy> setReorderPolicy(
            UUID variantId, ReorderPolicyRequest request, @Nullable String ifMatch) {
        var saved = policies.set(
                variantId,
                request.getLocationId(),
                new BigDecimal(request.getReorderLevel()),
                new BigDecimal(request.getReorderQty()),
                request.getPreferredSupplierId(),
                ifMatch);
        return ResponseEntity.ok()
                .eTag(Etags.of(saved.version()))
                .body(new ReorderPolicy()
                        .variantId(saved.variantId())
                        .locationId(saved.locationId())
                        .reorderLevel(mapper.decimal(saved.reorderLevel()))
                        .reorderQty(mapper.decimal(saved.reorderQty()))
                        .preferredSupplierId(saved.preferredSupplierId())
                        .version(saved.version()));
    }
}
