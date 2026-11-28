package com.bmp.payments.application;

import com.bmp.payments.api.PaymentQueries;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.port.CashLedger;
import com.bmp.payments.domain.port.DrawerSessionRepository;
import com.bmp.payments.domain.service.ExpectedCash;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PaymentQueries} over the tenant's drawer sessions. */
@Component
class PaymentQueriesImpl implements PaymentQueries {

    private final DrawerSessionRepository sessions;
    private final CashLedger cash;

    PaymentQueriesImpl(DrawerSessionRepository sessions, CashLedger cash) {
        this.sessions = sessions;
        this.cash = cash;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DrawerSessionInfo> activeDrawer(UUID registerId) {
        return sessions.findActive(registerId).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DrawerSessionInfo> findDrawer(UUID sessionId) {
        return sessions.findById(sessionId).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DrawerSessionInfo> unreconciledOpenedBetween(Instant from, Instant to) {
        return sessions.unreconciledOpenedBetween(from, to).stream()
                .map(this::info)
                .toList();
    }

    @Transactional(readOnly = true)
    DrawerSessionInfo info(DrawerSession s) {
        return new DrawerSessionInfo(
                s.id(),
                s.registerId(),
                s.status().name(),
                s.openingFloat(),
                ExpectedCash.of(cash.ofSession(s.id())).expected(),
                s.countedCash(),
                s.variance(),
                s.openedAt());
    }
}
