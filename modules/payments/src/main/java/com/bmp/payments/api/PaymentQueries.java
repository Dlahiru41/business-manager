package com.bmp.payments.api;

import com.bmp.payments.api.dto.DrawerSessionInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to drawers for other modules, in the current tenant. */
public interface PaymentQueries {

    /** The register's drawer that takes cash (OPEN or COUNTED), with its expected cash now. */
    Optional<DrawerSessionInfo> activeDrawer(UUID registerId);

    Optional<DrawerSessionInfo> findDrawer(UUID sessionId);

    /** Drawer sessions opened in {@code [from, to)} that are not reconciled yet (FIN-06, period close). */
    List<DrawerSessionInfo> unreconciledOpenedBetween(Instant from, Instant to);
}
