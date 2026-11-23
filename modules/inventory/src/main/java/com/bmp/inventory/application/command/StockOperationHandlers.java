package com.bmp.inventory.application.command;

import com.bmp.inventory.application.InventoryEvents;
import com.bmp.inventory.application.InventorySettings;
import com.bmp.inventory.application.StockLedger;
import com.bmp.inventory.application.StockReferences;
import com.bmp.inventory.application.query.MovementView;
import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.ResolvedQuantity;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.model.WastageReason;
import com.bmp.inventory.domain.policy.NegativeStockPolicy;
import com.bmp.inventory.domain.port.MovementQueries;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.IdGenerator;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening balances (INV-17), wastage (INV-11) and transfers (INV-15), all through the ledger. */
@Service
public class StockOperationHandlers {

    private static final Logger log = LoggerFactory.getLogger(StockOperationHandlers.class);

    private final StockLedger ledger;
    private final StockReferences references;
    private final MovementQueries movements;
    private final InventorySettings settings;
    private final InventoryEvents events;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters")
    public StockOperationHandlers(
            StockLedger ledger,
            StockReferences references,
            MovementQueries movements,
            InventorySettings settings,
            InventoryEvents events,
            IdGenerator ids,
            Clock clock) {
        this.ledger = ledger;
        this.references = references;
        this.movements = movements;
        this.settings = settings;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /** A quantity of a variant, in a packaging unit (the base unit when none). */
    public record ItemQty(
            UUID variantId,
            @Nullable UUID packagingUnitId,
            @Nullable String stockState,
            @Nullable UUID batchId,
            BigDecimal qty) {}

    /** An opening balance line; the unit cost is per packaging unit. */
    public record OpeningLine(
            UUID variantId,
            @Nullable UUID packagingUnitId,
            @Nullable String stockState,
            BigDecimal qty,
            BigDecimal unitCost,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn) {}

    @Transactional
    @Observed(name = "bmp.inventory.opening-balances")
    public List<MovementView> openingBalances(UUID locationId, @Nullable Instant occurredAt, List<OpeningLine> lines) {
        references.location(locationId);
        Instant at = occurredAt != null ? occurredAt : clock.instant();
        UUID source = ids.newUuid();
        List<StockLine> stock = new ArrayList<>();
        Set<List<Object>> seen = new HashSet<>();
        for (OpeningLine line : lines) {
            StockItem item = references.item(line.variantId());
            ResolvedQuantity resolved = item.resolve(line.packagingUnitId(), line.stockState(), line.qty());
            UUID lineId = ids.newUuid();
            UUID batch = references.incomingBatch(item, line.batchCode(), line.expiresOn(), at, null, lineId);
            BalanceKey key = new BalanceKey(
                    item.variantId(), locationId, resolved.stockState(), batch != null ? batch : BalanceKey.NO_BATCH);
            ledger.lock(List.of(key));
            // Rule INV-17: once per variant, location and state (under the balance lock).
            if (!seen.add(List.of(item.variantId(), resolved.stockState()))
                    || movements.hasOpening(item.variantId(), locationId, resolved.stockState())) {
                throw new BusinessRuleViolation(
                        InventoryErrorCode.OPENING_BALANCE_EXISTS,
                        Map.of("variantId", item.variantId(), "stockState", resolved.stockState()));
            }
            BigDecimal cost = settings.rounding().round(line.qty().multiply(line.unitCost()));
            stock.add(new StockLine(
                    key,
                    MovementType.OPENING,
                    resolved.qtyBase(),
                    cost,
                    true,
                    "OPENING_BALANCE",
                    source,
                    lineId,
                    null,
                    at));
        }
        List<PlannedMovement> posted = ledger.post(stock);
        String currency = settings.tenant().currency();
        events.received("OPENING_BALANCE", source, locationId, currency, posted);
        log.atInfo()
                .addKeyValue("sourceId", source)
                .addKeyValue("lines", lines.size())
                .log("Opening balances posted");
        return views(posted, currency);
    }

    /** Rule INV-11: lost stock leaves at current cost; OTHER needs a note; follows the negative stock policy. */
    @Transactional
    @Observed(name = "bmp.inventory.adjustment")
    public List<MovementView> adjust(
            UUID locationId,
            WastageReason reason,
            @Nullable String note,
            @Nullable Instant occurredAt,
            List<ItemQty> lines) {
        if (reason.needsNote() && (note == null || note.isBlank())) {
            throw new ValidationFailure(List.of(new FieldError("note", "REQUIRED", "a note is required for OTHER")));
        }
        references.location(locationId);
        Instant at = occurredAt != null ? occurredAt : clock.instant();
        UUID source = ids.newUuid();
        boolean mayGoNegative = settings.negativeStockPolicy() == NegativeStockPolicy.ALLOW_AND_FLAG;
        List<StockLine> stock = new ArrayList<>();
        for (ItemQty line : lines) {
            BalanceKey key = key(line, locationId);
            stock.add(new StockLine(
                    key,
                    MovementType.WASTAGE,
                    quantity(line).negate(),
                    null,
                    mayGoNegative,
                    "ADJUSTMENT",
                    source,
                    ids.newUuid(),
                    reason.name(),
                    at));
        }
        List<PlannedMovement> posted = ledger.post(stock);
        String currency = settings.tenant().currency();
        events.adjusted(source, reason, note, locationId, currency, posted);
        log.atInfo()
                .addKeyValue("adjustmentId", source)
                .addKeyValue("reason", reason)
                .log("Stock adjusted");
        return views(posted, currency);
    }

    /** Rule INV-15: a TRANSFER_OUT and a TRANSFER_IN per line at current cost, in one transaction. */
    @Transactional
    @Observed(name = "bmp.inventory.transfer")
    public List<MovementView> transfer(
            UUID fromLocationId, UUID toLocationId, @Nullable Instant occurredAt, List<ItemQty> lines) {
        if (fromLocationId.equals(toLocationId)) {
            throw new BusinessRuleViolation(
                    InventoryErrorCode.TRANSFER_SAME_LOCATION, Map.of("locationId", fromLocationId));
        }
        references.location(fromLocationId);
        references.location(toLocationId);
        Instant at = occurredAt != null ? occurredAt : clock.instant();
        UUID source = ids.newUuid();
        boolean mayGoNegative = settings.negativeStockPolicy() == NegativeStockPolicy.ALLOW_AND_FLAG;
        List<StockLine> outs = new ArrayList<>();
        List<BalanceKey> both = new ArrayList<>();
        for (ItemQty line : lines) {
            BalanceKey from = key(line, fromLocationId);
            both.add(from);
            both.add(new BalanceKey(from.variantId(), toLocationId, from.stockState(), from.batchId()));
            outs.add(new StockLine(
                    from,
                    MovementType.TRANSFER_OUT,
                    quantity(line).negate(),
                    null,
                    mayGoNegative,
                    "TRANSFER",
                    source,
                    ids.newUuid(),
                    null,
                    at));
        }
        ledger.lock(both);
        List<PlannedMovement> out = ledger.post(outs);
        List<StockLine> ins = out.stream()
                .map(m -> new StockLine(
                        new BalanceKey(
                                m.key().variantId(),
                                toLocationId,
                                m.key().stockState(),
                                m.key().batchId()),
                        MovementType.TRANSFER_IN,
                        m.qtyBase().negate(),
                        m.costAmount().negate(),
                        true,
                        "TRANSFER",
                        source,
                        m.sourceLineId(),
                        null,
                        at))
                .toList();
        List<PlannedMovement> in = ledger.post(ins);
        List<PlannedMovement> all = new ArrayList<>(out);
        all.addAll(in);
        log.atInfo()
                .addKeyValue("transferId", source)
                .addKeyValue("lines", lines.size())
                .log("Stock transferred");
        return views(all, settings.tenant().currency());
    }

    private BalanceKey key(ItemQty line, UUID locationId) {
        StockItem item = references.item(line.variantId());
        ResolvedQuantity resolved = item.resolve(line.packagingUnitId(), line.stockState(), line.qty());
        UUID batch = line.batchId() == null ? BalanceKey.NO_BATCH : references.batch(item.variantId(), line.batchId());
        return new BalanceKey(item.variantId(), locationId, resolved.stockState(), batch);
    }

    private BigDecimal quantity(ItemQty line) {
        return references
                .item(line.variantId())
                .resolve(line.packagingUnitId(), line.stockState(), line.qty())
                .qtyBase();
    }

    private List<MovementView> views(List<PlannedMovement> posted, String currency) {
        Instant now = clock.instant();
        UUID actor = ActorContext.currentOrSystem().userId() == null
                ? null
                : java.util.Objects.requireNonNull(
                                ActorContext.currentOrSystem().userId())
                        .value();
        return posted.stream()
                .map(m -> MovementView.of(m, currency, now, actor))
                .toList();
    }
}
