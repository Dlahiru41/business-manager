package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.service.BalanceReplay;
import java.util.List;
import java.util.Map;

/** The current tenant's ledger and balance keys, for rebuilding balances (INV-16). */
public interface LedgerReader {

    /** Every movement by balance, in posting order (recorded time, then id). */
    Map<BalanceKey, List<BalanceReplay.Recorded>> movementsByBalance();

    /** The keys of every balance row. */
    List<BalanceKey> balanceKeys();
}
