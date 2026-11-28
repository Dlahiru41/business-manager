package com.bmp.finance;

import com.bmp.payments.api.PaymentCommands;
import com.bmp.payments.api.PaymentQueries;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Payments' drawer API as finance sees it: sessions the test opens, and the cash movements finance records. */
public class FakeDrawers implements PaymentQueries, PaymentCommands {

    private final Map<UUID, DrawerSessionInfo> sessions = new ConcurrentHashMap<>();
    private final List<CashMovementCommand> movements = new CopyOnWriteArrayList<>();

    /** The code payments answers with for a drawer that is not open. */
    enum Code implements ErrorCode {
        DRAWER_NOT_OPEN;

        @Override
        public String code() {
            return name();
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.CONFLICT;
        }

        @Override
        public String module() {
            return "payments";
        }
    }

    public UUID open(UUID register, Instant openedAt) {
        UUID id = UUID.randomUUID();
        sessions.put(
                id,
                new DrawerSessionInfo(id, register, "OPEN", BigDecimal.ZERO, BigDecimal.ZERO, null, null, openedAt));
        return id;
    }

    public void reconcile(UUID session) {
        DrawerSessionInfo s = sessions.get(session);
        sessions.put(
                session,
                new DrawerSessionInfo(
                        s.id(),
                        s.registerId(),
                        "RECONCILED",
                        s.openingFloat(),
                        s.expectedCash(),
                        s.expectedCash(),
                        BigDecimal.ZERO,
                        s.openedAt()));
    }

    public List<CashMovementCommand> movements(UUID session) {
        return movements.stream().filter(m -> m.sessionId().equals(session)).toList();
    }

    @Override
    public Optional<DrawerSessionInfo> activeDrawer(UUID registerId) {
        return sessions.values().stream()
                .filter(s -> s.registerId().equals(registerId))
                .findFirst();
    }

    @Override
    public Optional<DrawerSessionInfo> findDrawer(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<DrawerSessionInfo> unreconciledOpenedBetween(Instant from, Instant to) {
        return sessions.values().stream()
                .filter(s -> !s.status().equals("RECONCILED"))
                .filter(s -> !s.openedAt().isBefore(from) && s.openedAt().isBefore(to))
                .toList();
    }

    @Override
    public DrawerSessionInfo openDrawer(OpenDrawer command) {
        throw new UnsupportedOperationException("finance never opens drawers");
    }

    @Override
    public void recordCashMovement(CashMovementCommand command) {
        DrawerSessionInfo session = sessions.get(command.sessionId());
        if (!session.status().equals("OPEN")) {
            throw new BusinessRuleViolation(Code.DRAWER_NOT_OPEN, Map.of("sessionId", command.sessionId()));
        }
        movements.add(command);
    }

    @Override
    public DrawerSessionInfo countDrawer(CountDrawer command) {
        throw new UnsupportedOperationException("finance never counts drawers");
    }
}
