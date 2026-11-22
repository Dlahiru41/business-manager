package com.bmp.tenant.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business settings of one group changed ({@code bmp.tenant.settings-changed.v1}); the aggregate id is the tenant and
 * the aggregate version the new group version.
 *
 * @param group the setting group, e.g. {@code sales}
 * @param changes the changed keys with their new values
 */
public record SettingsChanged(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        String group,
        List<Change> changes)
        implements DomainEvent {

    public SettingsChanged {
        changes = List.copyOf(changes);
    }

    /**
     * One changed key.
     *
     * @param key setting key, e.g. {@code maxCashierDiscountPct}
     * @param value the new value as JSON text
     */
    public record Change(String key, String value) {}
}
