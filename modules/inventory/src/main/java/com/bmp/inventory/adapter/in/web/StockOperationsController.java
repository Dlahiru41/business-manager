package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.api.StockOperationsApi;
import com.bmp.inventory.adapter.in.web.generated.model.AdjustmentRequest;
import com.bmp.inventory.adapter.in.web.generated.model.OpeningBalanceRequest;
import com.bmp.inventory.adapter.in.web.generated.model.PostedMovements;
import com.bmp.inventory.adapter.in.web.generated.model.TransferRequest;
import com.bmp.inventory.application.command.StockOperationHandlers;
import com.bmp.inventory.domain.model.WastageReason;
import com.bmp.web.idempotency.Idempotent;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Opening balances, wastage and transfers (INV-11, INV-15, INV-17). */
@RestController
class StockOperationsController implements StockOperationsApi {

    private final StockOperationHandlers handlers;
    private final InventoryWebMapper mapper;

    StockOperationsController(StockOperationHandlers handlers, InventoryWebMapper mapper) {
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:adjust')")
    public ResponseEntity<PostedMovements> postOpeningBalances(UUID idempotencyKey, OpeningBalanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.posted(handlers.openingBalances(
                        request.getLocationId(),
                        InventoryRequests.instant(request.getOccurredAt()),
                        request.getLines().stream()
                                .map(l -> new StockOperationHandlers.OpeningLine(
                                        l.getVariantId(),
                                        l.getPackagingUnitId(),
                                        l.getStockState(),
                                        new BigDecimal(l.getQty()),
                                        new BigDecimal(l.getUnitCost()),
                                        l.getBatchCode(),
                                        l.getExpiresOn()))
                                .toList())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:adjust')")
    public ResponseEntity<PostedMovements> postAdjustment(UUID idempotencyKey, AdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.posted(handlers.adjust(
                        request.getLocationId(),
                        WastageReason.valueOf(request.getReason().name()),
                        request.getNote(),
                        InventoryRequests.instant(request.getOccurredAt()),
                        InventoryRequests.lines(request.getLines()))));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:transfer')")
    public ResponseEntity<PostedMovements> postTransfer(UUID idempotencyKey, TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.posted(handlers.transfer(
                        request.getFromLocationId(),
                        request.getToLocationId(),
                        InventoryRequests.instant(request.getOccurredAt()),
                        InventoryRequests.lines(request.getLines()))));
    }
}
