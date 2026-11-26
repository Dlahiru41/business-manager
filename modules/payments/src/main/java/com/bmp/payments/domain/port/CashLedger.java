package com.bmp.payments.domain.port;

import com.bmp.payments.domain.model.CashMovement;
import java.util.List;
import java.util.UUID;

/** The {@code cash_movement} ledger of the current tenant: rows are appended, never changed. */
public interface CashLedger {

    void append(CashMovement movement);

    boolean exists(UUID movementId);

    /** A drawer's movements in the order they occurred. */
    List<CashMovement> ofSession(UUID drawerSessionId);
}
