package com.bmp.payments.application;

import com.bmp.payments.api.PaymentCommands;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.payments.application.command.DrawerHandlers;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.payments.domain.model.DenominationCount;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PaymentCommands} for sync (SYN-04): what a till did offline, each in its own transaction; the ids the till
 * gave make a replay change nothing. The device is the caller, so the drawer-owner check does not apply.
 */
@Component
class PaymentCommandsImpl implements PaymentCommands {

    private final DrawerHandlers drawers;
    private final PaymentQueriesImpl queries;

    PaymentCommandsImpl(DrawerHandlers drawers, PaymentQueriesImpl queries) {
        this.drawers = drawers;
        this.queries = queries;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DrawerSessionInfo openDrawer(OpenDrawer c) {
        return queries.info(drawers.open(c.sessionId(), c.registerId(), c.openingFloat(), c.openedAt(), c.cashierId()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCashMovement(CashMovementCommand c) {
        drawers.record(c.sessionId(), c.movementId(), type(c.type()), c.amount(), c.reason(), c.occurredAt(), false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DrawerSessionInfo countDrawer(CountDrawer c) {
        return queries.info(drawers.count(
                c.sessionId(),
                c.denominations().stream()
                        .map(d -> new DenominationCount(d.value(), d.count()))
                        .toList(),
                c.countedAt(),
                false));
    }

    private static CashMovementType type(String type) {
        try {
            return CashMovementType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "Unknown cash movement type", Map.of("field", "type"));
        }
    }
}
