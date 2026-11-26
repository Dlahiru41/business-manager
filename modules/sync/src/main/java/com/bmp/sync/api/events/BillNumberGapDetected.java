package com.bmp.sync.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Rule SYN-06: bill numbers of a register that never arrived although later ones did; each reported once. */
public record BillNumberGapDetected(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID registerId,
        String billPrefix,
        List<String> missingNumbers)
        implements DomainEvent {

    public BillNumberGapDetected {
        missingNumbers = List.copyOf(missingNumbers);
    }
}
