package com.bmp.party.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A customer or supplier was added (PTY-01) — {@code bmp.party.party-created.v1}. Carries no phone, e-mail, tax id or address (07 §1).
 *
 * @param type CUSTOMER, SUPPLIER or BOTH
 * @param status ACTIVE or INACTIVE
 * @param currency the currency of the credit limit (the tenant's)
 */
public record PartyCreated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        PartyId partyId,
        String type,
        String displayName,
        @Nullable UUID customerGroupId,
        String status,
        boolean anonymised,
        String currency,
        CreditPolicySnapshot creditPolicy)
        implements DomainEvent {}
