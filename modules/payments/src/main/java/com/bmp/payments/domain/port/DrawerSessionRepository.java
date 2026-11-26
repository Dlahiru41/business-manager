package com.bmp.payments.domain.port;

import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.DrawerStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Drawer sessions of the current tenant. */
public interface DrawerSessionRepository {

    Optional<DrawerSession> findById(UUID id);

    /** Locks the session for a count, a reconciliation or a cash movement. */
    Optional<DrawerSession> lock(UUID id);

    /** The register's session that takes cash: OPEN or COUNTED, until reconciled. */
    Optional<DrawerSession> findActive(UUID registerId);

    /** Newest first, from before {@code beforeId}. */
    List<DrawerSession> page(
            @Nullable UUID registerId, @Nullable DrawerStatus status, @Nullable UUID beforeId, int limit);

    DrawerSession saveAndFlush(DrawerSession session);
}
