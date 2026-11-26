package com.bmp.inventory.application.command;

import com.bmp.inventory.application.InventoryEvents;
import com.bmp.inventory.application.StockLedger;
import com.bmp.inventory.application.StockReferences;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.port.MovementQueries;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.SaleReversal;
import com.bmp.inventory.domain.service.SaleReversal.Back;
import com.bmp.inventory.domain.service.SaleReversal.Booked;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.persistence.event.OutOfOrderEventException;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales in stock (T03-04, INV-05..INV-07, SAL-11): a completed bill issues one SALE movement per line at current cost,
 * never refused for lack of stock; a return brings its restocked lines back at the cost they left with (lines not
 * restocked move nothing: the goods left on the sale and their cost stays there); a void reverses whatever of the bill
 * is still out. Returns and voids of a bill whose sale is not booked yet are retried later
 * ({@link OutOfOrderEventException}).
 */
@Service
public class SaleStockHandler {

    static final String SALE = "SALE";
    static final String SALE_RETURN = "SALE_RETURN";
    static final String SALE_VOID = "SALE_VOID";
    private static final Logger log = LoggerFactory.getLogger(SaleStockHandler.class);

    private final StockLedger ledger;
    private final StockReferences references;
    private final MovementQueries movements;
    private final InventoryEvents events;

    public SaleStockHandler(
            StockLedger ledger, StockReferences references, MovementQueries movements, InventoryEvents events) {
        this.ledger = ledger;
        this.references = references;
        this.movements = movements;
        this.events = events;
    }

    /** A bill line as sales completed it, in base units and the stock state of its packaging unit (INV-05, INV-06). */
    public record SoldLine(UUID lineId, UUID variantId, String stockState, BigDecimal qtyBase) {}

    /** A completed bill. */
    public record Sale(
            UUID eventId, UUID billId, UUID locationId, Instant completedAt, String currency, List<SoldLine> lines) {}

    /** A returned bill line that goes back to stock. */
    public record RestockedLine(UUID billLineId, BigDecimal qtyBase) {}

    /** A return of a bill: only its restocked lines. */
    public record Return(
            UUID eventId,
            UUID creditNoteId,
            UUID billId,
            UUID locationId,
            Instant returnedAt,
            String currency,
            List<RestockedLine> lines) {}

    /** A voided bill. */
    public record Voided(UUID eventId, UUID billId, UUID locationId, Instant voidedAt, String currency) {}

    @Transactional
    @Observed(name = "bmp.inventory.sale-issued")
    public List<PlannedMovement> sold(Sale sale) {
        List<StockLine> stock = new ArrayList<>();
        for (SoldLine line : sale.lines()) {
            // Not replicated yet: retried later, the bill is completed and final.
            StockItem item = references
                    .findItem(line.variantId())
                    .orElseThrow(() -> new OutOfOrderEventException(sale.eventId(), "variant " + line.variantId()));
            if (line.qtyBase().signum() <= 0) {
                log.atWarn()
                        .addKeyValue("billId", sale.billId())
                        .addKeyValue("lineId", line.lineId())
                        .log("Sold line without a base quantity issues no stock");
                continue;
            }
            stock.add(new StockLine(
                    BalanceKey.of(item.variantId(), sale.locationId(), line.stockState()),
                    MovementType.SALE,
                    line.qtyBase().negate(),
                    null,
                    true,
                    SALE,
                    sale.billId(),
                    line.lineId(),
                    null,
                    sale.completedAt()));
        }
        List<PlannedMovement> posted = ledger.post(stock);
        publish(SALE, sale.billId(), sale.billId(), sale.locationId(), sale.currency(), posted);
        return posted;
    }

    @Transactional
    @Observed(name = "bmp.inventory.sale-returned")
    public List<PlannedMovement> returned(Return r) {
        if (r.lines().isEmpty()) {
            log.debug("Return restocks nothing creditNoteId={}", r.creditNoteId());
            return List.of();
        }
        List<Booked> booked = lockedMovements(r.billId());
        List<StockLine> stock = new ArrayList<>();
        for (RestockedLine line : r.lines()) {
            if (!SaleReversal.issued(booked, line.billLineId())) {
                throw new OutOfOrderEventException(r.eventId(), "sale of bill line " + line.billLineId());
            }
            for (Back back : SaleReversal.restock(booked, line.billLineId(), line.qtyBase())) {
                stock.add(inbound(back, MovementType.SALE_RETURN, SALE_RETURN, r.creditNoteId(), r.returnedAt()));
            }
        }
        List<PlannedMovement> posted = ledger.post(stock);
        publish(SALE_RETURN, r.creditNoteId(), r.billId(), r.locationId(), r.currency(), posted);
        return posted;
    }

    @Transactional
    @Observed(name = "bmp.inventory.sale-voided")
    public List<PlannedMovement> voided(Voided v) {
        List<Booked> booked = lockedMovements(v.billId());
        if (booked.stream().noneMatch(b -> b.qtyBase().signum() < 0)) {
            throw new OutOfOrderEventException(v.eventId(), "sale of bill " + v.billId());
        }
        List<StockLine> stock = SaleReversal.reverseAll(booked).stream()
                .map(back -> inbound(back, MovementType.SALE_VOID, SALE_VOID, v.billId(), v.voidedAt()))
                .toList();
        if (stock.isEmpty()) {
            // Sales voids only bills without returns; should one come anyway, nothing is put back twice.
            log.atWarn().addKeyValue("billId", v.billId()).log("Voided bill has no stock left to put back");
            return List.of();
        }
        List<PlannedMovement> posted = ledger.post(stock);
        publish(SALE_VOID, v.billId(), v.billId(), v.locationId(), v.currency(), posted);
        return posted;
    }

    /** The bill's movements read after locking their balances, so a concurrent return or void waits and is seen. */
    private List<Booked> lockedMovements(UUID billId) {
        List<Booked> before = movements.billMovements(billId);
        if (before.isEmpty()) {
            return before;
        }
        ledger.lock(before.stream().map(Booked::key).distinct().toList());
        return movements.billMovements(billId);
    }

    private static StockLine inbound(Back back, MovementType type, String sourceType, UUID sourceId, Instant at) {
        return new StockLine(
                back.key(),
                type,
                back.qtyBase(),
                back.cost(),
                true,
                sourceType,
                sourceId,
                back.sourceLineId(),
                null,
                at);
    }

    private void publish(
            String sourceType,
            UUID sourceId,
            UUID billId,
            UUID locationId,
            String currency,
            List<PlannedMovement> posted) {
        if (posted.isEmpty()) {
            return;
        }
        events.issued(sourceType, sourceId, billId, locationId, currency, posted);
        log.atInfo()
                .addKeyValue("sourceType", sourceType)
                .addKeyValue("billId", billId)
                .addKeyValue("movements", posted.size())
                .log("Sale booked in stock");
    }
}
