package com.bmp.procurement.application.command;

import com.bmp.procurement.application.ProcurementEvents;
import com.bmp.procurement.application.ProcurementLoader;
import com.bmp.procurement.application.ProcurementReferences;
import com.bmp.procurement.application.ProcurementSettings;
import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.OrderLine;
import com.bmp.procurement.domain.model.Purchasable;
import com.bmp.procurement.domain.model.PurchaseOrder;
import com.bmp.procurement.domain.model.ReceiptLine;
import com.bmp.procurement.domain.port.GoodsReceiptRepository;
import com.bmp.procurement.domain.port.PurchaseOrderRepository;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.security.AccessPolicy;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Goods receipts (PRO-02..PRO-04, PRO-07): drafts, posting (which publishes GoodsReceived) and returns. */
@Service
public class GoodsReceiptHandlers {

    static final String CORRECT = "procurement:correct";
    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptHandlers.class);

    private final GoodsReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final ProcurementLoader loader;
    private final ProcurementReferences references;
    private final ProcurementSettings settings;
    private final ProcurementEvents events;
    private final AccessPolicy access;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters")
    public GoodsReceiptHandlers(
            GoodsReceiptRepository receipts,
            PurchaseOrderRepository orders,
            ProcurementLoader loader,
            ProcurementReferences references,
            ProcurementSettings settings,
            ProcurementEvents events,
            AccessPolicy access,
            IdGenerator ids,
            Clock clock) {
        this.receipts = receipts;
        this.orders = orders;
        this.loader = loader;
        this.references = references;
        this.settings = settings;
        this.events = events;
        this.access = access;
        this.ids = ids;
        this.clock = clock;
    }

    /** A received line; {@code qty} and {@code unitCost} are in the packaging unit. */
    public record LineInput(
            @Nullable UUID purchaseOrderLineId,
            UUID variantId,
            UUID packagingUnitId,
            BigDecimal qty,
            BigDecimal unitCost,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn) {}

    public record CreateReceipt(
            UUID supplierId,
            UUID locationId,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID correctsReceiptId,
            @Nullable Instant receivedAt,
            @Nullable String notes,
            List<LineInput> lines) {}

    @Transactional
    @Observed(name = "bmp.procurement.create-receipt")
    public GoodsReceipt create(CreateReceipt command) {
        references.supplier(command.supplierId());
        references.location(command.locationId());
        PurchaseOrder order = command.purchaseOrderId() == null ? null : order(command);
        List<GoodsReceipt.Line> lines = lines(command.lines(), order);
        GoodsReceipt receipt = GoodsReceipt.draft(
                ids.newUuid(),
                TenantContext.require(),
                settings.nextReceiptNumber(),
                command.supplierId(),
                command.locationId(),
                command.purchaseOrderId(),
                command.correctsReceiptId(),
                command.receivedAt() != null ? command.receivedAt() : clock.instant(),
                settings.currency(),
                command.notes(),
                lines,
                settings.rounding(),
                ids::newUuid);
        requireCorrectionPermission(receipt);
        requireReturnable(receipt, false);
        return saved(receipt);
    }

    @Transactional
    @Observed(name = "bmp.procurement.update-receipt")
    public GoodsReceipt update(
            UUID receiptId,
            long expectedVersion,
            @Nullable Instant receivedAt,
            @Nullable String notes,
            @Nullable List<LineInput> lines) {
        GoodsReceipt receipt = loader.receipt(receiptId);
        IfMatch.check(expectedVersion, receipt.version());
        if (receivedAt != null) {
            receipt.changeReceivedAt(receivedAt);
        }
        if (notes != null) {
            receipt.changeNotes(notes);
        }
        if (lines != null) {
            PurchaseOrder order = receipt.purchaseOrderId() == null ? null : loader.order(receipt.purchaseOrderId());
            receipt.replaceLines(lines(lines, order), settings.rounding(), ids::newUuid);
        }
        requireCorrectionPermission(receipt);
        requireReturnable(receipt, false);
        return saved(receipt);
    }

    /** Rule PRO-02, PRO-04: posting is final, adds to the order's received quantities and publishes GoodsReceived. */
    @Transactional
    @Observed(name = "bmp.procurement.post-receipt")
    public GoodsReceipt post(UUID receiptId) {
        GoodsReceipt receipt = loader.receipt(receiptId);
        requireCorrectionPermission(receipt);
        requireReturnable(receipt, true);
        var user = ActorContext.currentOrSystem().userId();
        receipt.post(clock.instant(), user == null ? null : user.value());
        if (receipt.purchaseOrderId() != null && !receipt.isReturn()) {
            PurchaseOrder order = loader.order(receipt.purchaseOrderId());
            Map<UUID, BigDecimal> received = new LinkedHashMap<>();
            for (ReceiptLine line : receipt.lines()) {
                if (line.purchaseOrderLineId() != null) {
                    received.merge(line.purchaseOrderLineId(), line.qty(), BigDecimal::add);
                }
            }
            order.received(received);
            orders.saveAndFlush(order);
        }
        GoodsReceipt saved = saved(receipt);
        events.goodsReceived(saved);
        log.atInfo()
                .addKeyValue("receiptId", saved.id())
                .addKeyValue("number", saved.number())
                .addKeyValue("lines", saved.lines().size())
                .log("Goods receipt posted");
        return saved;
    }

    @Transactional
    @Observed(name = "bmp.procurement.cancel-receipt")
    public GoodsReceipt cancel(UUID receiptId) {
        GoodsReceipt receipt = loader.receipt(receiptId);
        receipt.cancel();
        return saved(receipt);
    }

    private PurchaseOrder order(CreateReceipt command) {
        PurchaseOrder order = orders.findById(java.util.Objects.requireNonNull(command.purchaseOrderId()))
                .filter(o -> o.supplierId().equals(command.supplierId())
                        && o.locationId().equals(command.locationId()))
                .orElseThrow(() -> new BusinessRuleViolation(
                        ProcurementErrorCode.PURCHASE_ORDER_MISMATCH,
                        Map.of("purchaseOrderId", java.util.Objects.requireNonNull(command.purchaseOrderId()))));
        boolean isReturn = command.lines().stream().anyMatch(l -> l.qty().signum() < 0);
        if (!order.isOpen() && !isReturn) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID,
                    Map.of("status", order.status().name()));
        }
        order.lines();
        return order;
    }

    /** Rules PRO-01, PRO-03, INV-14: buyable units, order lines of the order, expiry where tracked. */
    private List<GoodsReceipt.Line> lines(List<LineInput> inputs, @Nullable PurchaseOrder order) {
        return inputs.stream()
                .map(l -> {
                    Purchasable unit = references.purchasable(l.variantId(), l.packagingUnitId());
                    if (l.purchaseOrderLineId() != null) {
                        OrderLine ordered = order == null
                                ? null
                                : order.line(l.purchaseOrderLineId()).orElse(null);
                        if (ordered == null
                                || !ordered.variantId().equals(l.variantId())
                                || !ordered.packagingUnitId().equals(l.packagingUnitId())) {
                            throw new BusinessRuleViolation(
                                    ProcurementErrorCode.PURCHASE_ORDER_MISMATCH,
                                    Map.of("purchaseOrderLineId", l.purchaseOrderLineId()));
                        }
                    }
                    if (unit.trackExpiry() && l.qty().signum() > 0 && l.expiresOn() == null) {
                        throw new BusinessRuleViolation(
                                ProcurementErrorCode.EXPIRY_REQUIRED, Map.of("variantId", l.variantId()));
                    }
                    return new GoodsReceipt.Line(
                            l.purchaseOrderLineId(), unit, l.qty(), l.unitCost(), l.batchCode(), l.expiresOn());
                })
                .toList();
    }

    /** Rule PRO-02: checked again when posting, under the corrected receipt's lock, so returns cannot race. */
    private void requireReturnable(GoodsReceipt receipt, boolean lock) {
        UUID correctsId = receipt.correctsReceiptId();
        if (correctsId == null) {
            return;
        }
        GoodsReceipt corrected = (lock
                        ? receipts.lockAll(List.of(correctsId)).stream().findFirst()
                        : receipts.findById(correctsId))
                .orElseThrow(() -> new BusinessRuleViolation(
                        ProcurementErrorCode.CORRECTION_TARGET_INVALID, Map.of("correctsReceiptId", correctsId)));
        receipt.requireReturnableFrom(corrected, receipts.findPostedCorrections(correctsId));
    }

    /** Rule PRO-02: a return (negative lines) needs {@code procurement:correct}. */
    private void requireCorrectionPermission(GoodsReceipt receipt) {
        if (receipt.isReturn() && !access.isGranted(CORRECT)) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.CORRECTION_PERMISSION_REQUIRED, Map.of("permission", CORRECT));
        }
    }

    private GoodsReceipt saved(GoodsReceipt receipt) {
        GoodsReceipt saved = receipts.saveAndFlush(receipt);
        saved.lines();
        return saved;
    }
}
