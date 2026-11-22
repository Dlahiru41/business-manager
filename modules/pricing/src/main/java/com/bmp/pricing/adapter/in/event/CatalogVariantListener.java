package com.bmp.pricing.adapter.in.event;

import com.bmp.catalog.api.events.PackagingUnitSnapshot;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.pricing.application.command.ReplicateVariantHandler;
import com.bmp.pricing.domain.model.VariantRef;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the pricing replica of catalog variants (T01-05). Each event is applied once (inbox); snapshots carry the
 * catalog's aggregate version, so one arriving after a newer one changes nothing.
 */
@Component
class CatalogVariantListener {

    static final String CONSUMER = "pricing.CatalogVariantListener";
    private static final String ACTIVE = "ACTIVE";

    private static final Logger log = LoggerFactory.getLogger(CatalogVariantListener.class);

    private final InboxGuard inbox;
    private final ReplicateVariantHandler replicas;

    CatalogVariantListener(InboxGuard inbox, ReplicateVariantHandler replicas) {
        this.inbox = inbox;
        this.replicas = replicas;
    }

    @ApplicationModuleListener
    void on(VariantCreated event) {
        if (first(event.eventId())) {
            replicas.replicate(ref(
                    event.variantId().value(),
                    event.productId().value(),
                    event.taxCategoryId(),
                    event.status(),
                    event.aggregateVersion(),
                    event.packagingUnits()));
        }
    }

    @ApplicationModuleListener
    void on(VariantUpdated event) {
        if (first(event.eventId())) {
            replicas.replicate(ref(
                    event.variantId().value(),
                    event.productId().value(),
                    event.taxCategoryId(),
                    event.status(),
                    event.aggregateVersion(),
                    event.packagingUnits()));
        }
    }

    @ApplicationModuleListener
    void on(VariantDeactivated event) {
        if (first(event.eventId())) {
            replicas.deactivate(event.variantId().value(), event.productId().value(), event.aggregateVersion());
        }
    }

    private boolean first(UUID eventId) {
        boolean first = inbox.firstDelivery(CONSUMER, eventId);
        if (!first) {
            log.debug("Skipping redelivered event eventId={}", eventId);
        }
        return first;
    }

    private static VariantRef ref(
            UUID variantId,
            UUID productId,
            @Nullable UUID taxCategoryId,
            String status,
            long version,
            List<PackagingUnitSnapshot> units) {
        return new VariantRef(
                variantId,
                productId,
                taxCategoryId,
                ACTIVE.equals(status),
                version,
                units.stream()
                        .map(u -> new VariantRef.Unit(
                                u.id().value(),
                                u.code(),
                                u.qtyInBase(),
                                u.isBase(),
                                u.canSell(),
                                ACTIVE.equals(u.status())))
                        .toList());
    }
}
