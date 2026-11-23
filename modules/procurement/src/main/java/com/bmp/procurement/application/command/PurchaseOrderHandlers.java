package com.bmp.procurement.application.command;

import com.bmp.procurement.api.spi.ReorderSuggestion;
import com.bmp.procurement.api.spi.ReorderSuggestionProvider;
import com.bmp.procurement.application.ProcurementLoader;
import com.bmp.procurement.application.ProcurementReferences;
import com.bmp.procurement.application.ProcurementSettings;
import com.bmp.procurement.domain.model.Purchasable;
import com.bmp.procurement.domain.model.PurchaseOrder;
import com.bmp.procurement.domain.port.PurchaseOrderRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase orders (PRO-01, PRO-06, PRO-07). */
@Service
public class PurchaseOrderHandlers {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderHandlers.class);

    private final PurchaseOrderRepository orders;
    private final ProcurementLoader loader;
    private final ProcurementReferences references;
    private final ProcurementSettings settings;
    private final ReorderSuggestionProvider suggestions;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters")
    public PurchaseOrderHandlers(
            PurchaseOrderRepository orders,
            ProcurementLoader loader,
            ProcurementReferences references,
            ProcurementSettings settings,
            ReorderSuggestionProvider suggestions,
            IdGenerator ids,
            Clock clock) {
        this.orders = orders;
        this.loader = loader;
        this.references = references;
        this.settings = settings;
        this.suggestions = suggestions;
        this.ids = ids;
        this.clock = clock;
    }

    /** A line to order: a quantity of a packaging unit at a cost per unit. */
    public record LineInput(UUID variantId, UUID packagingUnitId, BigDecimal qty, BigDecimal unitCost) {}

    /** Draft orders made from the reorder list, and what could not be ordered. */
    public record FromReorderList(List<PurchaseOrder> orders, List<UUID> skippedVariantIds) {}

    @Transactional
    @Observed(name = "bmp.procurement.create-order")
    public PurchaseOrder create(
            UUID supplierId,
            UUID locationId,
            @Nullable LocalDate expectedOn,
            @Nullable String notes,
            List<LineInput> lines) {
        references.supplier(supplierId);
        references.location(locationId);
        PurchaseOrder saved = orders.saveAndFlush(PurchaseOrder.draft(
                ids.newUuid(),
                TenantContext.require(),
                settings.nextOrderNumber(),
                supplierId,
                locationId,
                settings.currency(),
                expectedOn,
                notes,
                lines(lines),
                ids::newUuid));
        log.atInfo()
                .addKeyValue("orderId", saved.id())
                .addKeyValue("number", saved.number())
                .log("Purchase order drafted");
        return saved;
    }

    @Transactional
    @Observed(name = "bmp.procurement.update-order")
    public PurchaseOrder update(
            UUID orderId,
            long expectedVersion,
            @Nullable LocalDate expectedOn,
            @Nullable String notes,
            @Nullable List<LineInput> lines) {
        PurchaseOrder order = loader.order(orderId);
        IfMatch.check(expectedVersion, order.version());
        if (expectedOn != null) {
            order.changeExpectedOn(expectedOn);
        }
        if (notes != null) {
            order.changeNotes(notes);
        }
        if (lines != null) {
            order.replaceLines(lines(lines), ids::newUuid);
        }
        return saved(order);
    }

    @Transactional
    @Observed(name = "bmp.procurement.send-order")
    public PurchaseOrder send(UUID orderId) {
        PurchaseOrder order = loader.order(orderId);
        order.send(clock.instant());
        return saved(order);
    }

    @Transactional
    @Observed(name = "bmp.procurement.cancel-order")
    public PurchaseOrder cancel(UUID orderId) {
        PurchaseOrder order = loader.order(orderId);
        order.cancel(clock.instant());
        return saved(order);
    }

    /**
     * Rule PRO-06: one draft order per preferred supplier and location, each variant's reorder quantity in its first
     * buyable unit (rounded up) at the last known cost.
     */
    @Transactional
    @Observed(name = "bmp.procurement.orders-from-reorder-list")
    public FromReorderList fromReorderList(@Nullable UUID locationId) {
        if (locationId != null) {
            references.location(locationId);
        }
        Map<List<UUID>, List<LineInput>> grouped = new LinkedHashMap<>();
        List<UUID> skipped = new ArrayList<>();
        for (ReorderSuggestion suggestion :
                suggestions.suggestions(locationId == null ? null : LocationId.of(locationId))) {
            UUID variant = suggestion.variantId().value();
            Optional<Purchasable> unit = references.firstPurchasable(variant);
            if (suggestion.preferredSupplierId() == null || unit.isEmpty()) {
                skipped.add(variant);
                continue;
            }
            BigDecimal qtyInBase = unit.get().qtyInBase();
            BigDecimal qty = suggestion
                    .reorderQtyBase()
                    .divide(qtyInBase, 0, RoundingMode.CEILING)
                    .max(BigDecimal.ONE);
            BigDecimal cost = suggestion.lastUnitCostBase() == null
                    ? BigDecimal.ZERO
                    : suggestion.lastUnitCostBase().multiply(qtyInBase).setScale(6, RoundingMode.HALF_UP);
            grouped.computeIfAbsent(
                            List.of(
                                    suggestion.preferredSupplierId().value(),
                                    suggestion.locationId().value()),
                            k -> new ArrayList<>())
                    .add(new LineInput(variant, unit.get().packagingUnitId(), qty, cost));
        }
        List<PurchaseOrder> drafted = new ArrayList<>();
        grouped.forEach(
                (key, lines) -> drafted.add(create(key.get(0), key.get(1), null, "From the reorder list", lines)));
        return new FromReorderList(drafted, skipped);
    }

    private List<PurchaseOrder.Line> lines(List<LineInput> inputs) {
        return inputs.stream()
                .map(l -> new PurchaseOrder.Line(
                        references.purchasable(l.variantId(), l.packagingUnitId()), l.qty(), l.unitCost()))
                .toList();
    }

    private PurchaseOrder saved(PurchaseOrder order) {
        PurchaseOrder saved = orders.saveAndFlush(order);
        saved.lines();
        return saved;
    }
}
