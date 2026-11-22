package com.bmp.catalog.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * A transformation definition was retired (CAT-11): no new runs may use it —
 * {@code bmp.catalog.transformation-definition-retired.v1}.
 *
 * @param definitionId the definition
 * @param version its last published version (0 when it was never published)
 */
public record TransformationDefinitionRetired(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID definitionId,
        int version)
        implements DomainEvent {}
