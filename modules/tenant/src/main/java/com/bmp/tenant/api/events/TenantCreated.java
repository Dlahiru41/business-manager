package com.bmp.tenant.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tenant was created ({@code bmp.tenant.tenant-created.v1}). Consumers seed their per-tenant defaults: iam its
 * system roles, catalog/finance/pricing their templates in later phases.
 *
 * @param businessType free classification chosen at creation
 * @param currency ISO 4217 code
 * @param timeZone IANA time zone
 * @param businessDayCutoffHour 0–23
 * @param templatePackId onboarding template pack (phase 05), if any
 */
public record TenantCreated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        String businessType,
        String currency,
        String timeZone,
        int businessDayCutoffHour,
        @Nullable String templatePackId)
        implements DomainEvent {}
