package com.bmp.inventory.application.command;

import com.bmp.inventory.application.InventoryEvents;
import com.bmp.inventory.application.StockLedger;
import com.bmp.inventory.application.StockReferences;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.StockLine;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules PRO-04, INV-03, INV-14: a posted goods receipt becomes one RECEIPT movement per line. Deliveries come in at
 * their line total, into the batch they name (or a generated one); returns to the supplier leave at current cost.
 * Stock that physically moved is always recorded: receipts may take a balance below zero and inactive variants still
 * receive.
 */
@Service
public class GoodsReceiptStockHandler {

    static final String SOURCE = "GOODS_RECEIPT";
    static final String RETURN_REASON = "RETURN_TO_SUPPLIER";
    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptStockHandler.class);

    private final StockLedger ledger;
    private final StockReferences references;
    private final InventoryEvents events;

    public GoodsReceiptStockHandler(StockLedger ledger, StockReferences references, InventoryEvents events) {
        this.ledger = ledger;
        this.references = references;
        this.events = events;
    }

    /**
     * A line as procurement posted it.
     *
     * @param qtyBase signed: negative on a return
     * @param lineTotal the cost of a delivered line
     */
    public record ReceivedLine(
            UUID lineId,
            UUID variantId,
            String stockState,
            BigDecimal qtyBase,
            BigDecimal lineTotal,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn) {}

    /** A posted goods receipt. */
    public record Receipt(
            UUID receiptId,
            UUID supplierId,
            UUID locationId,
            Instant receivedAt,
            String currency,
            List<ReceivedLine> lines) {}

    @Transactional
    @Observed(name = "bmp.inventory.goods-received")
    public List<PlannedMovement> receive(Receipt receipt) {
        List<StockLine> stock = new ArrayList<>();
        for (ReceivedLine line : receipt.lines()) {
            StockItem item = references.knownItem(line.variantId());
            boolean delivered = line.qtyBase().signum() > 0;
            UUID batch = delivered
                    ? references.incomingBatch(
                            item,
                            line.batchCode(),
                            line.expiresOn(),
                            receipt.receivedAt(),
                            receipt.supplierId(),
                            line.lineId())
                    : null;
            stock.add(new StockLine(
                    new BalanceKey(
                            item.variantId(),
                            receipt.locationId(),
                            line.stockState(),
                            batch != null ? batch : BalanceKey.NO_BATCH),
                    MovementType.RECEIPT,
                    line.qtyBase(),
                    delivered ? line.lineTotal() : null,
                    true,
                    SOURCE,
                    receipt.receiptId(),
                    line.lineId(),
                    delivered ? null : RETURN_REASON,
                    receipt.receivedAt()));
        }
        List<PlannedMovement> posted = ledger.post(stock);
        events.received(SOURCE, receipt.receiptId(), receipt.locationId(), receipt.currency(), posted);
        log.atInfo()
                .addKeyValue("receiptId", receipt.receiptId())
                .addKeyValue("movements", posted.size())
                .log("Goods receipt booked into stock");
        return posted;
    }
}
