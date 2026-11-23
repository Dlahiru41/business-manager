package com.bmp.inventory.adapter.in.event;

import com.bmp.catalog.api.events.PackagingUnitSnapshot;
import com.bmp.catalog.api.events.ProductCreated;
import com.bmp.catalog.api.events.ProductTracking;
import com.bmp.catalog.api.events.ProductUpdated;
import com.bmp.catalog.api.events.TransformationDefinitionPublished;
import com.bmp.catalog.api.events.TransformationDefinitionRetired;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.inventory.application.command.ReplicateCatalogHandler;
import com.bmp.inventory.domain.model.DefinitionLine;
import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.model.StockUnit;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.shared.id.PackagingUnitId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Replicates catalog products, variants and published definitions (T02-03); the inbox makes redelivery harmless. */
@Component
class CatalogListener {

    static final String CONSUMER = "inventory.CatalogListener";
    private static final String ACTIVE = "ACTIVE";

    private static final Logger log = LoggerFactory.getLogger(CatalogListener.class);

    private final InboxGuard inbox;
    private final ReplicateCatalogHandler replicas;

    CatalogListener(InboxGuard inbox, ReplicateCatalogHandler replicas) {
        this.inbox = inbox;
        this.replicas = replicas;
    }

    @ApplicationModuleListener
    void on(ProductCreated event) {
        if (first(event.eventId())) {
            replicas.product(event.productId().value(), event.name(), event.categoryId(), event.aggregateVersion());
        }
    }

    @ApplicationModuleListener
    void on(ProductUpdated event) {
        if (first(event.eventId())) {
            replicas.product(event.productId().value(), event.name(), event.categoryId(), event.aggregateVersion());
        }
    }

    @ApplicationModuleListener
    void on(VariantCreated event) {
        if (first(event.eventId())) {
            replicas.variant(item(
                    event.variantId().value(),
                    event.productId().value(),
                    event.sku(),
                    event.name(),
                    event.baseUnit(),
                    event.stockStates(),
                    event.sellMode(),
                    event.packagingUnits(),
                    event.tracking(),
                    event.status(),
                    event.aggregateVersion()));
        }
    }

    @ApplicationModuleListener
    void on(VariantUpdated event) {
        if (first(event.eventId())) {
            replicas.variant(item(
                    event.variantId().value(),
                    event.productId().value(),
                    event.sku(),
                    event.name(),
                    event.baseUnit(),
                    event.stockStates(),
                    event.sellMode(),
                    event.packagingUnits(),
                    event.tracking(),
                    event.status(),
                    event.aggregateVersion()));
        }
    }

    @ApplicationModuleListener
    void on(VariantDeactivated event) {
        if (first(event.eventId())) {
            replicas.variantDeactivated(
                    event.variantId().value(), event.productId().value(), event.sku(), event.aggregateVersion());
        }
    }

    @ApplicationModuleListener
    void on(TransformationDefinitionPublished event) {
        if (first(event.eventId())) {
            replicas.definitionPublished(new DefinitionRef(
                    event.definitionId(),
                    event.version(),
                    event.code(),
                    event.name(),
                    event.kind(),
                    event.expectedYieldPct(),
                    event.lines().stream()
                            .map(l -> new DefinitionLine(
                                    l.direction(),
                                    l.variantId().value(),
                                    l.stockState(),
                                    l.qtyBase(),
                                    id(l.packagingUnitId()),
                                    l.costSharePct()))
                            .toList(),
                    true));
        }
    }

    @ApplicationModuleListener
    void on(TransformationDefinitionRetired event) {
        if (first(event.eventId())) {
            replicas.definitionRetired(event.definitionId(), event.version());
        }
    }

    private boolean first(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return true;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return false;
    }

    private static @org.jspecify.annotations.Nullable UUID id(@org.jspecify.annotations.Nullable PackagingUnitId unit) {
        return unit == null ? null : unit.value();
    }

    @SuppressWarnings("TooManyParameters") // one per snapshot field the replica keeps
    private static StockItem item(
            UUID variantId,
            UUID productId,
            String sku,
            String name,
            String baseUnit,
            List<String> states,
            String sellMode,
            List<PackagingUnitSnapshot> units,
            ProductTracking tracking,
            String status,
            long version) {
        return new StockItem(
                variantId,
                productId,
                sku,
                name,
                baseUnit,
                states,
                sellMode,
                units.stream()
                        .map(u -> new StockUnit(
                                u.id().value(),
                                u.code(),
                                u.qtyInBase(),
                                u.stockState(),
                                u.isBase(),
                                u.canBuy(),
                                u.canSell(),
                                u.status().equals(ACTIVE)))
                        .toList(),
                tracking.batches(),
                tracking.expiry(),
                tracking.decimalQty(),
                status.equals(ACTIVE),
                version);
    }
}
