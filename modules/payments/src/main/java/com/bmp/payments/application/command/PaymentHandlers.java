package com.bmp.payments.application.command;

import com.bmp.party.api.PartyQueries;
import com.bmp.party.api.dto.PartyInfo;
import com.bmp.payments.application.PaymentsEvents;
import com.bmp.payments.application.PaymentsSettings;
import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.payments.domain.model.CashMovement;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.DrawerStatus;
import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.model.PaymentKind;
import com.bmp.payments.domain.model.PaymentMethod;
import com.bmp.payments.domain.model.Refund;
import com.bmp.payments.domain.port.CashLedger;
import com.bmp.payments.domain.port.DrawerSessionRepository;
import com.bmp.payments.domain.port.PaymentLedger;
import com.bmp.payments.domain.port.PaymentLedger.Captured;
import com.bmp.payments.domain.service.RefundAllocation;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.PartyId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules PAY-01..PAY-03, PAY-05: payments for bill tenders and account collections, refunds at the counter and for
 * returns and voids. Money that physically moved is always recorded: cash outside an open drawer is logged at WARN and
 * counted instead of refused; a return or void whose sale is not recorded yet is retried
 * ({@link OutOfOrderEventException}).
 */
@Service
public class PaymentHandlers {

    private static final Logger log = LoggerFactory.getLogger(PaymentHandlers.class);

    private final PaymentLedger ledger;
    private final CashLedger cash;
    private final DrawerSessionRepository sessions;
    private final DrawerHandlers drawers;
    private final PartyQueries parties;
    private final PaymentsSettings settings;
    private final PaymentsEvents events;
    private final IdGenerator ids;
    private final Clock clock;
    private final MeterRegistry meters;

    @SuppressWarnings("TooManyParameters") // the ports and platform services payments need
    public PaymentHandlers(
            PaymentLedger ledger,
            CashLedger cash,
            DrawerSessionRepository sessions,
            DrawerHandlers drawers,
            PartyQueries parties,
            PaymentsSettings settings,
            PaymentsEvents events,
            IdGenerator ids,
            Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        this.ledger = ledger;
        this.cash = cash;
        this.sessions = sessions;
        this.drawers = drawers;
        this.parties = parties;
        this.settings = settings;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.meters = meters.getIfUnique(SimpleMeterRegistry::new);
    }

    /** A bill tender as sales completed it. */
    public record SaleTender(
            UUID tenderId,
            String method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable BigDecimal change,
            @Nullable String reference) {}

    /** A completed bill. */
    public record Sale(
            UUID eventId,
            UUID billId,
            @Nullable UUID customerId,
            UUID registerId,
            @Nullable UUID deviceId,
            LocalDate businessDate,
            Instant completedAt,
            String currency,
            List<SaleTender> tenders) {}

    /** How part of a return is paid back. */
    public record ReturnRefund(UUID refundId, String method, BigDecimal amount) {}

    /** A return of a bill. */
    public record SaleReturn(
            UUID eventId,
            UUID creditNoteId,
            UUID billId,
            UUID registerId,
            String reason,
            Instant returnedAt,
            List<ReturnRefund> refunds) {}

    /** A voided bill; {@code moneyTaken} when any of its tenders was not CREDIT. */
    public record SaleVoid(
            UUID eventId, UUID billId, UUID registerId, String reason, Instant voidedAt, boolean moneyTaken) {}

    /** Rule PAY-02: a collection on a customer's account. */
    public record Collect(
            UUID id,
            UUID customerId,
            PaymentMethod method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable String reference,
            @Nullable UUID registerId) {}

    /** Rule PAY-03: a refund at the counter. */
    public record RefundCommand(
            UUID id,
            BigDecimal amount,
            String reason,
            @Nullable PaymentMethod method,
            @Nullable UUID registerId) {}

    /** Rule PAY-01: one payment per tender other than CREDIT; cash lands in the register's drawer. */
    @Transactional
    @Observed(name = "bmp.payments.capture-sale")
    public List<Payment> captureSale(Sale sale) {
        Optional<DrawerSession> drawer = sessions.findActive(sale.registerId());
        List<Payment> captured = new ArrayList<>();
        for (SaleTender tender : sale.tenders()) {
            Optional<PaymentMethod> method = PaymentMethod.ofTender(tender.method());
            if (method.isEmpty() || ledger.findByTender(tender.tenderId()).isPresent()) {
                continue; // CREDIT is owed, not paid; a tender seen before is not paid twice
            }
            boolean cashIn = method.get() == PaymentMethod.CASH;
            UUID session = cashIn ? drawer.map(DrawerSession::id).orElse(null) : null;
            Payment payment = new Payment(
                    ids.newUuid(),
                    PaymentKind.BILL,
                    sale.billId(),
                    tender.tenderId(),
                    sale.customerId(),
                    method.get(),
                    tender.amount(),
                    sale.currency(),
                    tender.tendered(),
                    tender.change(),
                    tender.reference(),
                    sale.registerId(),
                    session,
                    sale.deviceId(),
                    sale.businessDate(),
                    sale.completedAt());
            ledger.append(payment);
            if (cashIn) {
                cashMovement(
                        session, CashMovementType.SALE, payment.amount(), "PAYMENT", payment.id(), sale.completedAt());
            }
            events.captured(payment);
            captured.add(payment);
        }
        if (!captured.isEmpty()) {
            log.atInfo()
                    .addKeyValue("billId", sale.billId())
                    .addKeyValue("payments", captured.size())
                    .log("Bill payments captured");
        }
        return captured;
    }

    /** Rule PAY-02: money a customer pays on account; cash goes into an OPEN drawer of the register. */
    @Transactional
    @Observed(name = "bmp.payments.collect")
    public Payment collect(Collect c) {
        Optional<Captured> known = ledger.find(c.id());
        if (known.isPresent()) {
            return known.get().payment();
        }
        PartyInfo customer = parties.findParty(PartyId.of(c.customerId()))
                .filter(p -> p.active() && p.isCustomer())
                .orElseThrow(() -> new BusinessRuleViolation(
                        PaymentsErrorCode.UNKNOWN_CUSTOMER, Map.of("customerId", c.customerId())));
        BigDecimal change = Payment.checkTender(
                c.method(), c.amount(), c.tendered(), c.reference(), settings.tenderReferenceRequired());
        UUID session = null;
        if (c.method() == PaymentMethod.CASH) {
            session = openDrawer(required(c.registerId())).id();
        } else if (c.registerId() != null) {
            drawers.register(c.registerId());
        }
        Instant now = clock.instant();
        Payment payment = new Payment(
                c.id(),
                PaymentKind.ACCOUNT,
                null,
                null,
                customer.id().value(),
                c.method(),
                c.amount(),
                settings.currency(),
                c.method() == PaymentMethod.CASH ? (c.tendered() != null ? c.tendered() : c.amount()) : null,
                change,
                c.reference(),
                c.registerId(),
                session,
                null,
                settings.businessDate(now),
                now);
        ledger.append(payment);
        if (session != null) {
            cash.append(new CashMovement(
                    ids.newUuid(),
                    session,
                    CashMovementType.PAID_IN,
                    payment.amount(),
                    null,
                    "PAYMENT",
                    payment.id(),
                    now));
        }
        events.captured(payment);
        log.atInfo()
                .addKeyValue("paymentId", payment.id())
                .addKeyValue("method", payment.method())
                .addKeyValue("amount", payment.amount())
                .log("Account payment captured");
        return payment;
    }

    /** Rule PAY-03: at most what is left of the payment; cash comes out of an OPEN drawer of the register. */
    @Transactional
    @Observed(name = "bmp.payments.refund")
    public Refund refund(UUID paymentId, RefundCommand r) {
        ledger.lock(List.of(paymentId));
        Captured captured = ledger.find(paymentId)
                .orElseThrow(() ->
                        new NotFoundException(PaymentsErrorCode.PAYMENT_NOT_FOUND, Map.of("paymentId", paymentId)));
        if (ledger.refundExists(r.id())) {
            return ledger.refundsOf(paymentId).stream()
                    .filter(f -> f.id().equals(r.id()))
                    .findFirst()
                    .orElseThrow();
        }
        Payment.checkReference(r.reason());
        if (r.amount().signum() <= 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "A refund gives money back", Map.of("field", "amount"));
        }
        if (r.amount().compareTo(captured.remaining()) > 0) {
            throw new BusinessRuleViolation(
                    PaymentsErrorCode.REFUND_EXCEEDS_PAYMENT,
                    Map.of("remaining", captured.remaining().toPlainString()));
        }
        Payment payment = captured.payment();
        PaymentMethod method = r.method() != null ? r.method() : payment.method();
        UUID registerId = r.registerId() != null ? r.registerId() : payment.registerId();
        UUID session = null;
        if (method == PaymentMethod.CASH) {
            session = openDrawer(required(registerId)).id();
        }
        Instant now = clock.instant();
        Refund refund = new Refund(
                r.id(),
                paymentId,
                r.amount(),
                method,
                payment.currency(),
                r.reason(),
                null,
                null,
                registerId,
                session,
                settings.businessDate(now),
                now);
        record(refund, payment);
        return refund;
    }

    /**
     * Rule PAY-03 for a return (T03-07): each refund tender other than CREDIT is taken from the bill's payments, those
     * of its method first; CREDIT reduces what the customer owes and moves no money.
     */
    @Transactional
    @Observed(name = "bmp.payments.refund-return")
    public List<Refund> refundReturn(SaleReturn r) {
        List<ReturnRefund> money = r.refunds().stream()
                .filter(f -> PaymentMethod.ofTender(f.method()).isPresent())
                .toList();
        if (money.isEmpty()) {
            return List.of();
        }
        List<Captured> payments = lockedPaymentsOf(r.eventId(), r.billId());
        Map<UUID, BigDecimal> refunded = refundedSoFar(payments);
        Optional<DrawerSession> drawer = sessions.findActive(r.registerId());
        List<Refund> given = new ArrayList<>();
        for (ReturnRefund f : money) {
            if (ledger.refundExists(f.refundId())) {
                continue;
            }
            PaymentMethod method = PaymentMethod.ofTender(f.method()).orElseThrow();
            RefundAllocation.Plan plan = RefundAllocation.allocate(f.amount(), method, balances(payments, refunded));
            for (int i = 0; i < plan.parts().size(); i++) {
                RefundAllocation.Part part = plan.parts().get(i);
                Payment payment = paymentOf(payments, part.paymentId());
                UUID id = i == 0 ? f.refundId() : derived(f.refundId(), part.paymentId());
                Refund refund = new Refund(
                        id,
                        part.paymentId(),
                        part.amount(),
                        method,
                        payment.currency(),
                        r.reason(),
                        r.creditNoteId(),
                        r.eventId(),
                        r.registerId(),
                        method == PaymentMethod.CASH
                                ? drawer.map(DrawerSession::id).orElse(null)
                                : null,
                        settings.businessDate(r.returnedAt()),
                        r.returnedAt());
                record(refund, payment);
                refunded.merge(part.paymentId(), part.amount(), BigDecimal::add);
                given.add(refund);
            }
            unallocated(plan, r.billId());
        }
        return given;
    }

    /** Rule PAY-03 for a void (T03-07): every payment of the bill is given back in full, by its own method. */
    @Transactional
    @Observed(name = "bmp.payments.refund-void")
    public List<Refund> refundVoid(SaleVoid v) {
        if (!v.moneyTaken()) {
            return List.of();
        }
        List<Captured> payments = lockedPaymentsOf(v.eventId(), v.billId());
        Optional<DrawerSession> drawer = sessions.findActive(v.registerId());
        List<Refund> given = new ArrayList<>();
        for (Captured c : payments) {
            UUID id = derived(v.billId(), c.payment().id());
            if (c.remaining().signum() <= 0 || ledger.refundExists(id)) {
                continue;
            }
            Payment payment = c.payment();
            Refund refund = new Refund(
                    id,
                    payment.id(),
                    c.remaining(),
                    payment.method(),
                    payment.currency(),
                    "VOID: " + v.reason(),
                    null,
                    v.eventId(),
                    v.registerId(),
                    payment.method() == PaymentMethod.CASH
                            ? drawer.map(DrawerSession::id).orElse(null)
                            : null,
                    settings.businessDate(v.voidedAt()),
                    v.voidedAt());
            record(refund, payment);
            given.add(refund);
        }
        return given;
    }

    private void record(Refund refund, Payment payment) {
        ledger.append(refund);
        if (refund.method() == PaymentMethod.CASH) {
            cashMovement(
                    refund.drawerSessionId(),
                    CashMovementType.REFUND,
                    refund.amount(),
                    "REFUND",
                    refund.id(),
                    refund.createdAt());
        }
        events.refunded(refund, payment);
        log.atInfo()
                .addKeyValue("refundId", refund.id())
                .addKeyValue("paymentId", payment.id())
                .addKeyValue("method", refund.method())
                .addKeyValue("amount", refund.amount())
                .log("Payment refunded");
    }

    private void cashMovement(
            @Nullable UUID session,
            CashMovementType type,
            BigDecimal amount,
            String source,
            UUID sourceId,
            Instant at) {
        if (session == null) {
            // The cash moved anyway: recorded on the payment or refund, flagged for the drawer's reconciliation.
            meters.counter("bmp.payments.cash_outside_drawer", "type", type.name())
                    .increment();
            log.atWarn()
                    .addKeyValue("type", type)
                    .addKeyValue("sourceId", sourceId)
                    .log("Cash moved while the register had no drawer open");
            return;
        }
        cash.append(new CashMovement(ids.newUuid(), session, type, amount, null, source, sourceId, at));
    }

    private List<Captured> lockedPaymentsOf(UUID eventId, UUID billId) {
        List<Captured> before = ledger.ofBill(billId);
        if (before.isEmpty()) {
            throw new OutOfOrderEventException(eventId, "payments of bill " + billId);
        }
        ledger.lock(before.stream().map(c -> c.payment().id()).toList());
        return ledger.ofBill(billId);
    }

    private static Map<UUID, BigDecimal> refundedSoFar(List<Captured> payments) {
        Map<UUID, BigDecimal> refunded = new HashMap<>();
        payments.forEach(c -> refunded.put(c.payment().id(), c.refunded()));
        return refunded;
    }

    private static List<RefundAllocation.Balance> balances(List<Captured> payments, Map<UUID, BigDecimal> refunded) {
        return payments.stream()
                .map(c -> new RefundAllocation.Balance(
                        c.payment().id(),
                        c.payment().method(),
                        c.payment().amount(),
                        refunded.getOrDefault(c.payment().id(), BigDecimal.ZERO)))
                .toList();
    }

    private static Payment paymentOf(List<Captured> payments, UUID paymentId) {
        return payments.stream()
                .map(Captured::payment)
                .filter(p -> p.id().equals(paymentId))
                .findFirst()
                .orElseThrow();
    }

    private void unallocated(RefundAllocation.Plan plan, UUID billId) {
        if (plan.unallocated().signum() > 0) {
            meters.counter("bmp.payments.refunds_unallocated").increment();
            log.atWarn()
                    .addKeyValue("billId", billId)
                    .addKeyValue("unallocated", plan.unallocated())
                    .log("Refund above what the bill's payments hold");
        }
    }

    private DrawerSession openDrawer(UUID registerId) {
        drawers.register(registerId);
        return sessions.findActive(registerId)
                .filter(s -> s.status() == DrawerStatus.OPEN)
                .orElseThrow(() ->
                        new BusinessRuleViolation(PaymentsErrorCode.DRAWER_NOT_OPEN, Map.of("registerId", registerId)));
    }

    private static UUID required(@Nullable UUID registerId) {
        if (registerId == null) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "Cash names its register", Map.of("field", "registerId"));
        }
        return registerId;
    }

    /** A stable id for a refund derived from what it refunds, so redelivery finds it. */
    static UUID derived(UUID first, UUID second) {
        return UUID.nameUUIDFromBytes((first + ":" + second).getBytes(StandardCharsets.UTF_8));
    }
}
