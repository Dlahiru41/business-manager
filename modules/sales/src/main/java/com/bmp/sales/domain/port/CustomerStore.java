package com.bmp.sales.domain.port;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The replica of customers, fed by party events (version-guarded); no contact details. */
public interface CustomerStore {

    /** A customer as sales needs it for credit sales (SAL-07). */
    record Customer(
            UUID partyId,
            String type,
            String displayName,
            @Nullable UUID customerGroupId,
            boolean active,
            boolean creditAllowed,
            BigDecimal creditLimit) {

        public boolean isCustomer() {
            return !"SUPPLIER".equals(type);
        }
    }

    Optional<Customer> find(UUID partyId);

    void upsert(Customer customer, long version);
}
