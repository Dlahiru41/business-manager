package com.bmp.party.application.query;

import com.bmp.party.domain.model.CustomerGroup;
import com.bmp.party.domain.model.PartyStatus;
import java.time.Instant;
import java.util.UUID;

/** A customer group. */
public record CustomerGroupView(
        UUID id, String code, String name, PartyStatus status, long version, Instant createdAt) {

    public static CustomerGroupView of(CustomerGroup group) {
        return new CustomerGroupView(
                group.id(),
                group.code(),
                group.name(),
                group.status(),
                group.version(),
                group.audit().createdAt());
    }
}
