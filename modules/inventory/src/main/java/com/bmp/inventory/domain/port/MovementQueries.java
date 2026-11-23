package com.bmp.inventory.domain.port;

import java.util.UUID;

/** Questions about the ledger that use cases ask. */
public interface MovementQueries {

    /** Rule INV-17: whether the variant has an opening balance in the location and state. */
    boolean hasOpening(UUID variantId, UUID locationId, String stockState);
}
