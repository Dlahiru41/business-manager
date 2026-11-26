package com.bmp.sales.adapter.in.event;

import com.bmp.catalog.api.events.PackagingUnitSnapshot;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.sales.application.command.ReplicaHandlers;
import com.bmp.sales.domain.port.SaleItemStore;
import java.util.List;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Replicates the sellable variants (T03-03); the inbox and the version guard make redelivery harmless. */
@Component
class SaleItemListener {

    static final String CONSUMER = "sales.SaleItemListener";

    private final InboxGuard inbox;
    private final ReplicaHandlers replicas;

    SaleItemListener(InboxGuard inbox, ReplicaHandlers replicas) {
        this.inbox = inbox;
        this.replicas = replicas;
    }

    @ApplicationModuleListener
    void on(VariantCreated e) {
        if (inbox.firstDelivery(CONSUMER, e.eventId())) {
            replicas.variant(
                    new SaleItemStore.SaleItem(
                            e.variantId().value(),
                            e.productId().value(),
                            e.sku(),
                            e.name(),
                            e.baseUnit(),
                            e.tracking().weighed(),
                            units(e.packagingUnits()),
                            "ACTIVE".equals(e.status())),
                    e.aggregateVersion());
        }
    }

    @ApplicationModuleListener
    void on(VariantUpdated e) {
        if (inbox.firstDelivery(CONSUMER, e.eventId())) {
            replicas.variant(
                    new SaleItemStore.SaleItem(
                            e.variantId().value(),
                            e.productId().value(),
                            e.sku(),
                            e.name(),
                            e.baseUnit(),
                            e.tracking().weighed(),
                            units(e.packagingUnits()),
                            "ACTIVE".equals(e.status())),
                    e.aggregateVersion());
        }
    }

    @ApplicationModuleListener
    void on(VariantDeactivated e) {
        if (inbox.firstDelivery(CONSUMER, e.eventId())) {
            replicas.variantDeactivated(e.variantId().value(), e.aggregateVersion());
        }
    }

    private static List<SaleItemStore.Unit> units(List<PackagingUnitSnapshot> units) {
        return units.stream()
                .map(u -> new SaleItemStore.Unit(
                        u.id().value(),
                        u.code(),
                        u.name(),
                        u.qtyInBase(),
                        u.stockState(),
                        u.isBase(),
                        u.canSell(),
                        "ACTIVE".equals(u.status())))
                .toList();
    }
}
