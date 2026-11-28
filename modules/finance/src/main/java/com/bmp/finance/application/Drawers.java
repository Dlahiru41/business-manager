package com.bmp.finance.application;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.payments.api.PaymentCommands;
import com.bmp.payments.api.PaymentQueries;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Cash finance moves through a register's drawer, recorded by payments (FIN-09, FIN-11, cash drops). */
@Component
public class Drawers {

    private final PaymentQueries queries;
    private final PaymentCommands commands;

    public Drawers(PaymentQueries queries, PaymentCommands commands) {
        this.queries = queries;
        this.commands = commands;
    }

    /**
     * Records the movement on the drawer session (payments refuses one that is not open) and returns the session.
     *
     * @param type PAID_OUT or DROP
     */
    public DrawerSessionInfo move(
            @Nullable UUID sessionId, UUID movementId, String type, BigDecimal amount, String reason, Instant at) {
        if (sessionId == null) {
            throw new BusinessRuleViolation(
                    FinanceErrorCode.DRAWER_SESSION_REQUIRED, Map.of("field", "drawerSessionId"));
        }
        DrawerSessionInfo session = queries.findDrawer(sessionId)
                .orElseThrow(() -> new BusinessRuleViolation(
                        FinanceErrorCode.DRAWER_SESSION_NOT_FOUND, Map.of("drawerSessionId", sessionId)));
        commands.recordCashMovement(new PaymentCommands.CashMovementCommand(
                movementId, sessionId, session.registerId(), type, amount, reason, at));
        return session;
    }
}
