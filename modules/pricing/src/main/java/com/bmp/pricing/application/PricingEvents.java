package com.bmp.pricing.application;

import com.bmp.pricing.api.events.PriceChangeSnapshot;
import com.bmp.pricing.api.events.PriceListChanged;
import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.service.PriceSchedule;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.PriceListId;
import com.bmp.shared.id.VariantId;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes {@code PriceListChanged} (PRC-03) from the saved list and the changes of its new version. */
@Component
public class PricingEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public PricingEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void priceListChanged(PriceList list, List<PriceSchedule.Change> changes) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new PriceListChanged(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                list.id(),
                list.version(),
                stamp.metadata(),
                PriceListId.of(list.id()),
                list.currentVersion(),
                list.code(),
                list.channel().name(),
                list.currency(),
                list.taxInclusive(),
                changes.stream().map(PricingEvents::snapshot).toList()));
    }

    private static PriceChangeSnapshot snapshot(PriceSchedule.Change change) {
        Price price = change.price();
        return new PriceChangeSnapshot(
                change.kind().name(),
                price.id(),
                VariantId.of(price.variantId()),
                PackagingUnitId.of(price.packagingUnitId()),
                price.minQty(),
                price.amount(),
                price.validFrom(),
                price.validTo());
    }
}
