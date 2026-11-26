package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.service.SaleReversal;
import java.util.List;
import java.util.UUID;

/** Questions about the ledger that use cases ask. */
public interface MovementQueries {

    /** Rule INV-17: whether the variant has an opening balance in the location and state. */
    boolean hasOpening(UUID variantId, UUID locationId, String stockState);

    /**
     * T03-04: the SALE, SALE_RETURN and SALE_VOID movements of a bill's lines, in posting order (recorded time, then
     * id); empty while the sale is not booked.
     */
    List<SaleReversal.Booked> billMovements(UUID billId);
}
