package com.bmp.party.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A party changed, its credit terms (PTY-03) and anonymisation (PTY-04) included — {@code bmp.party.party-updated.v1}. Carries no phone, e-mail, tax id or address (07 §1).
 *
 * @param type CUSTOMER, SUPPLIER or BOTH
 * @param status ACTIVE or INACTIVE
 * @param currency the currency of the credit limit (the tenant's)
 */
public record PartyUpdated(
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
