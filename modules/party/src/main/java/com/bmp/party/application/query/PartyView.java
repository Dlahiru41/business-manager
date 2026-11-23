package com.bmp.party.application.query;

import com.bmp.party.domain.model.Address;
import com.bmp.party.domain.model.CreditPolicy;
import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.model.PartyType;
import com.bmp.shared.security.Sensitive;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A party with its contact details (personal data). */
@Sensitive
public record PartyView(
        UUID id,
        PartyType type,
        String displayName,
        @Nullable String phone,
        @Nullable String email,
        @Nullable String taxId,
        @Nullable Address address,
        @Nullable UUID customerGroupId,
        PartyStatus status,
        boolean anonymised,
        CreditPolicy creditPolicy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static PartyView of(Party party) {
        return new PartyView(
                party.id(),
                party.type(),
                party.displayName(),
                party.phone(),
                party.email(),
                party.taxId(),
                party.address(),
                party.customerGroupId(),
                party.status(),
                party.anonymised(),
                party.creditPolicy(),
                party.version(),
                party.audit().createdAt(),
                party.audit().updatedAt());
    }

    @Override
    public String toString() {
        return "PartyView[id=" + id + ", type=" + type + "]";
    }
}
