package com.bmp.catalog.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A transformation definition version was published and is immutable from now on (CAT-10) —
 * {@code bmp.catalog.transformation-definition-published.v1}. Inventory keeps every version it receives.
 *
 * @param definitionId the definition
 * @param version the published version number
 * @param code unique per tenant
 * @param name display name
 * @param kind OPEN, REPACK, BREAK or RECIPE
 * @param expectedYieldPct expected output per input, in percent, if set
 * @param lines the version's lines
 */
public record TransformationDefinitionPublished(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID definitionId,
        int version,
        String code,
        String name,
        String kind,
        @Nullable BigDecimal expectedYieldPct,
        List<TransformationLineSnapshot> lines)
        implements DomainEvent {

    public TransformationDefinitionPublished {
        lines = List.copyOf(lines);
    }
}
