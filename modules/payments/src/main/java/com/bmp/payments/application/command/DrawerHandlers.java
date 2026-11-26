package com.bmp.payments.application.command;

import com.bmp.payments.application.PaymentsEvents;
import com.bmp.payments.application.PaymentsSettings;
import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.payments.domain.model.CashMovement;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.payments.domain.model.DenominationCount;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.port.CashLedger;
import com.bmp.payments.domain.port.DrawerSessionRepository;
import com.bmp.payments.domain.service.ExpectedCash;
import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.RegisterId;
import com.bmp.web.security.AccessPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule PAY-04: a register's drawer opens with a float, takes paid-outs, paid-ins and drops while open, is counted by
 * denomination and reconciled; a variance above the tenant's alert amount is logged at WARN and counted. Every command
 * carrying an id replays when the id is known (a till retrying).
 */
@Service
public class DrawerHandlers {

    static final String RECONCILE_PERMISSION = "payments:reconcile";
    private static final Logger log = LoggerFactory.getLogger(DrawerHandlers.class);

    private final DrawerSessionRepository sessions;
    private final CashLedger cash;
    private final PaymentsSettings settings;
    private final PaymentsEvents events;
    private final SalesQueries sales;
    private final AccessPolicy access;
    private final IdGenerator ids;
    private final Clock clock;
    private final MeterRegistry meters;

    @SuppressWarnings("TooManyParameters") // the ports and platform services drawers need
    public DrawerHandlers(
            DrawerSessionRepository sessions,
            CashLedger cash,
            PaymentsSettings settings,
            PaymentsEvents events,
            SalesQueries sales,
            AccessPolicy access,
            IdGenerator ids,
            Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        this.sessions = sessions;
        this.cash = cash;
        this.settings = settings;
        this.events = events;
        this.sales = sales;
        this.access = access;
        this.ids = ids;
        this.clock = clock;
        this.meters = meters.getIfUnique(SimpleMeterRegistry::new);
    }

    @Transactional
    @Observed(name = "bmp.payments.open-drawer")
    public DrawerSession open(
            @Nullable UUID id,
            UUID registerId,
            BigDecimal openingFloat,
            @Nullable Instant openedAt,
            @Nullable UUID cashier) {
        if (id != null) {
            var known = sessions.findById(id);
            if (known.isPresent()) {
                return known.get();
            }
        }
        register(registerId);
        if (sessions.findActive(registerId).isPresent()) {
            throw new BusinessRuleViolation(PaymentsErrorCode.DRAWER_ALREADY_OPEN, Map.of("registerId", registerId));
        }
        Instant at = openedAt != null ? openedAt : clock.instant();
        DrawerSession session = sessions.saveAndFlush(DrawerSession.open(
                id != null ? id : ids.newUuid(),
                TenantContext.require(),
                registerId,
                cashier != null ? cashier : currentUser(),
                openingFloat,
                settings.currency(),
                at));
        cash.append(new CashMovement(
                ids.newUuid(), session.id(), CashMovementType.FLOAT, openingFloat, null, "DRAWER", session.id(), at));
        log.atInfo()
                .addKeyValue("drawerSessionId", session.id())
                .addKeyValue("registerId", registerId)
                .log("Drawer opened");
        return session;
    }

    /** Paid-outs, paid-ins and drops: by the cashier who opened the drawer, or a holder of payments:reconcile. */
    @Transactional
    @Observed(name = "bmp.payments.cash-movement")
    public CashMovement record(
            UUID sessionId,
            @Nullable UUID id,
            CashMovementType type,
            BigDecimal amount,
            String reason,
            @Nullable Instant occurredAt,
            boolean checkOwner) {
        if (!type.manual()) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "Only paid-outs, paid-ins and drops are recorded by hand",
                    Map.of("field", "type"));
        }
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "A cash movement moves money", Map.of("field", "amount"));
        }
        Payment.checkReference(reason);
        UUID movementId = id != null ? id : ids.newUuid();
        DrawerSession session = locked(sessionId);
        if (cash.exists(movementId)) {
            return cash.ofSession(sessionId).stream()
                    .filter(m -> m.id().equals(movementId))
                    .findFirst()
                    .orElseThrow();
        }
        owner(session, checkOwner);
        session.requireOpen();
        CashMovement movement = new CashMovement(
                movementId,
                session.id(),
                type,
                amount,
                reason,
                "MANUAL",
                null,
                occurredAt != null ? occurredAt : clock.instant());
        cash.append(movement);
        log.atInfo()
                .addKeyValue("drawerSessionId", sessionId)
                .addKeyValue("type", type)
                .addKeyValue("amount", amount)
                .log("Cash movement recorded");
        return movement;
    }

    @Transactional
    @Observed(name = "bmp.payments.count-drawer")
    public DrawerSession count(
            UUID sessionId, List<DenominationCount> counted, @Nullable Instant countedAt, boolean checkOwner) {
        DrawerSession session = locked(sessionId);
        owner(session, checkOwner);
        session.count(
                counted,
                settings.cashDenominations(),
                expected(sessionId).expected(),
                currentUser(),
                countedAt != null ? countedAt : clock.instant());
        DrawerSession saved = sessions.saveAndFlush(session);
        log.atInfo()
                .addKeyValue("drawerSessionId", sessionId)
                .addKeyValue("variance", saved.variance())
                .log("Drawer counted");
        return saved;
    }

    @Transactional
    @Observed(name = "bmp.payments.reconcile-drawer")
    public DrawerSession reconcile(UUID sessionId, @Nullable String note) {
        DrawerSession session = locked(sessionId);
        session.reconcile(expected(sessionId).expected(), currentUser(), note, clock.instant());
        DrawerSession saved = sessions.saveAndFlush(session);
        events.reconciled(saved);
        BigDecimal variance = java.util.Objects.requireNonNull(saved.variance());
        if (variance.abs().compareTo(settings.drawerVarianceAlertAmount()) > 0) {
            meters.counter("bmp.payments.drawer_variance_alerts").increment();
            log.atWarn()
                    .addKeyValue("drawerSessionId", sessionId)
                    .addKeyValue("registerId", saved.registerId())
                    .addKeyValue("variance", variance)
                    .log("Drawer variance above the alert amount");
        }
        log.atInfo()
                .addKeyValue("drawerSessionId", sessionId)
                .addKeyValue("variance", variance)
                .log("Drawer reconciled");
        return saved;
    }

    /** Rule PAY-04 from the drawer's cash movements. */
    @Transactional(readOnly = true)
    public ExpectedCash expected(UUID sessionId) {
        return ExpectedCash.of(cash.ofSession(sessionId));
    }

    RegisterInfo register(UUID registerId) {
        return sales.findRegister(RegisterId.of(registerId))
                .filter(RegisterInfo::active)
                .orElseThrow(() -> new BusinessRuleViolation(
                        PaymentsErrorCode.UNKNOWN_REGISTER, Map.of("registerId", registerId)));
    }

    private DrawerSession locked(UUID sessionId) {
        return sessions.lock(sessionId)
                .orElseThrow(() -> new NotFoundException(
                        PaymentsErrorCode.DRAWER_SESSION_NOT_FOUND, Map.of("sessionId", sessionId)));
    }

    private void owner(DrawerSession session, boolean check) {
        if (check && !session.openedBy(currentUser()) && !access.isGranted(RECONCILE_PERMISSION)) {
            throw new BusinessRuleViolation(PaymentsErrorCode.DRAWER_NOT_YOURS, Map.of("sessionId", session.id()));
        }
    }

    static @Nullable UUID currentUser() {
        var user = ActorContext.currentOrSystem().userId();
        return user == null ? null : user.value();
    }
}
