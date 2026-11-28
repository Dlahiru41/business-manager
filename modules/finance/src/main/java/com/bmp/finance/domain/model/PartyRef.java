package com.bmp.finance.domain.model;

import java.util.Objects;
import java.util.UUID;

/** A customer or supplier named on a journal line or an open item. */
public record PartyRef(PartyType type, UUID id) {

    public PartyRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public static PartyRef customer(UUID id) {
        return new PartyRef(PartyType.CUSTOMER, id);
    }

    public static PartyRef supplier(UUID id) {
        return new PartyRef(PartyType.SUPPLIER, id);
    }
}
