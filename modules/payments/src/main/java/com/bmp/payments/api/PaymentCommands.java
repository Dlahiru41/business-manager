package com.bmp.payments.api;

import com.bmp.payments.api.dto.DrawerSessionInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Drawer commands for modules that replay what a till did offline (sync, SYN-04). Each runs in the caller's transaction, or its own
 * when there is none, in the current tenant, and is idempotent on its id: the same command again changes nothing and answers the same.
 */
public interface PaymentCommands {

    /** Opens a register's drawer with its float. */
    DrawerSessionInfo openDrawer(OpenDrawer command);

    /** Records a paid-out, paid-in or drop on an open drawer. */
    void recordCashMovement(CashMovementCommand command);

    /** Counts a drawer by denomination. */
    DrawerSessionInfo countDrawer(CountDrawer command);

    record OpenDrawer(
            UUID sessionId,
            UUID registerId,
            BigDecimal openingFloat,
            Instant openedAt,
            @Nullable UUID cashierId) {}

    /**
     * A cash movement recorded by hand.
     *
     * @param type PAID_OUT, PAID_IN or DROP
     */
    record CashMovementCommand(
            UUID movementId, UUID sessionId, String type, BigDecimal amount, String reason, Instant occurredAt) {}

    record Denomination(BigDecimal value, int count) {}

    record CountDrawer(
            UUID sessionId,
            List<Denomination> denominations,
            Instant countedAt,
            @Nullable UUID cashierId) {

        public CountDrawer {
            denominations = List.copyOf(denominations);
        }
    }
}
