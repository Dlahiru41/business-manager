package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.service.PlannedMovement;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stock balances of the current tenant (the ledger's projection, INV-01). */
public interface BalanceStore {

    /**
     * Creates the missing balances and locks all of them in key order (06 §7) until the transaction ends.
     *
     * @return the locked balances without FIFO layers
     */
    Map<BalanceKey, BalanceState> lock(Collection<BalanceKey> keys);

    /** The keys of the batch balances of a variant in a location and state (not locked). */
    List<BalanceKey> batchKeys(UUID variantId, UUID locationId, String stockState);

    /** Stores the balances after the movements; each balance's last movement is the last one on its key. */
    void update(Map<BalanceKey, BalanceState> balances, List<PlannedMovement> movements);

    /** Σ (on hand − reserved) of a variant in a location, every state and batch. */
    BigDecimal available(UUID variantId, UUID locationId);
}
