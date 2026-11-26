package com.bmp.payments.application;

import com.bmp.payments.api.PaymentCommands;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.payments.application.command.DrawerHandlers;
import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.payments.domain.model.DenominationCount;
import com.bmp.payments.domain.port.DrawerSessionRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PaymentCommands} for sync (SYN-04): what a till did offline, in the caller's transaction (sync runs one per
 * record); the ids the till gave make a replay change nothing. The device is the caller, so the drawer-owner check does
 * not apply; a till reaches only the drawer of its own register ({@code DRAWER_OF_ANOTHER_REGISTER}).
 */
@Component
class PaymentCommandsImpl implements PaymentCommands {

    private final DrawerHandlers drawers;
    private final PaymentQueriesImpl queries;
    private final DrawerSessionRepository sessions;

    PaymentCommandsImpl(DrawerHandlers drawers, PaymentQueriesImpl queries, DrawerSessionRepository sessions) {
        this.drawers = drawers;
        this.queries = queries;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public DrawerSessionInfo openDrawer(OpenDrawer c) {
        return queries.info(drawers.open(c.sessionId(), c.registerId(), c.openingFloat(), c.openedAt(), c.cashierId()));
    }

    @Override
    @Transactional
    public void recordCashMovement(CashMovementCommand c) {
        ofRegister(c.sessionId(), c.registerId());
        drawers.record(c.sessionId(), c.movementId(), type(c.type()), c.amount(), c.reason(), c.occurredAt(), false);
    }

    @Override
    @Transactional
    public DrawerSessionInfo countDrawer(CountDrawer c) {
        ofRegister(c.sessionId(), c.registerId());
        return queries.info(drawers.count(
                c.sessionId(),
                c.denominations().stream()
                        .map(d -> new DenominationCount(d.value(), d.count()))
                        .toList(),
                c.countedAt(),
                false));
    }

    /** The drawer must be the till's own register's; an unknown one is left to the handlers (not found). */
    private void ofRegister(UUID sessionId, UUID registerId) {
        sessions.findById(sessionId)
                .filter(s -> !s.registerId().equals(registerId))
                .ifPresent(s -> {
                    throw new BusinessRuleViolation(
                            PaymentsErrorCode.DRAWER_OF_ANOTHER_REGISTER,
                            Map.of("sessionId", sessionId, "registerId", registerId));
                });
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
