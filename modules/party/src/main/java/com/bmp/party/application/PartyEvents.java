package com.bmp.party.application;

import com.bmp.party.api.events.CreditPolicySnapshot;
import com.bmp.party.api.events.PartyCreated;
import com.bmp.party.api.events.PartyUpdated;
import com.bmp.party.domain.model.CreditPolicy;
import com.bmp.party.domain.model.Party;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.PartyId;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.TenantSummary;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes {@code PartyCreated} and {@code PartyUpdated} from the saved party; no contact details (07 §1). */
@Component
public class PartyEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;
    private final TenantQueries tenants;

    public PartyEvents(ApplicationEventPublisher publisher, EventStampSource stamps, TenantQueries tenants) {
        this.publisher = publisher;
        this.stamps = stamps;
        this.tenants = tenants;
    }

    public void created(Party party) {
        EventStamp stamp = stamps.next();
        String currency = currency();
        publisher.publishEvent(new PartyCreated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                party.id(),
                party.version(),
                stamp.metadata(),
                PartyId.of(party.id()),
                party.type().name(),
                party.displayName(),
                party.customerGroupId(),
                party.status().name(),
                party.anonymised(),
                currency,
                snapshot(party.creditPolicy())));
    }

    public void updated(Party party) {
        EventStamp stamp = stamps.next();
        String currency = currency();
        publisher.publishEvent(new PartyUpdated(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                party.id(),
                party.version(),
                stamp.metadata(),
                PartyId.of(party.id()),
                party.type().name(),
                party.displayName(),
                party.customerGroupId(),
                party.status().name(),
                party.anonymised(),
                currency,
                snapshot(party.creditPolicy())));
    }

    private String currency() {
        return tenants.currentTenant()
                .map(TenantSummary::currency)
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
    }

    private static CreditPolicySnapshot snapshot(CreditPolicy policy) {
        return new CreditPolicySnapshot(policy.creditAllowed(), policy.creditLimit(), policy.paymentTermsDays());
    }
}
