package com.bmp.party.api.dto;

import com.bmp.shared.id.PartyId;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A party as other modules see it: no phone, e-mail, tax id or address.
 *
 * @param type CUSTOMER, SUPPLIER or BOTH
 * @param active whether it may be used on new documents
 * @param creditLimit in the tenant currency
 * @param paymentTermsDays days until its invoices are due
 */
public record PartyInfo(
        PartyId id,
        String type,
        String displayName,
        @Nullable UUID customerGroupId,
        boolean active,
        boolean creditAllowed,
        BigDecimal creditLimit,
        int paymentTermsDays) {

    public boolean isCustomer() {
        return !type.equals("SUPPLIER");
    }

    public boolean isSupplier() {
        return !type.equals("CUSTOMER");
    }
}
