package com.bmp.payments.api;

import com.bmp.payments.api.dto.DrawerSessionInfo;
import java.util.Optional;
import java.util.UUID;

/** Read access to drawers for other modules, in the current tenant. */
public interface PaymentQueries {

    /** The register's drawer that takes cash (OPEN or COUNTED), with its expected cash now. */
    Optional<DrawerSessionInfo> activeDrawer(UUID registerId);

    Optional<DrawerSessionInfo> findDrawer(UUID sessionId);
}
