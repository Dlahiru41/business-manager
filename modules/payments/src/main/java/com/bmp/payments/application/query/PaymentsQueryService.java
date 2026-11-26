package com.bmp.payments.application.query;

import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.payments.domain.model.CashMovement;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.DrawerStatus;
import com.bmp.payments.domain.model.PaymentMethod;
import com.bmp.payments.domain.model.Refund;
import com.bmp.payments.domain.port.CashLedger;
import com.bmp.payments.domain.port.DrawerSessionRepository;
import com.bmp.payments.domain.port.PaymentLedger;
import com.bmp.payments.domain.port.PaymentLedger.Captured;
import com.bmp.payments.domain.port.PaymentLedger.MethodTotal;
import com.bmp.payments.domain.service.ExpectedCash;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Payments, daily totals (PAY-06) and drawer sessions with their expected cash (PAY-04). */
@Service
public class PaymentsQueryService {

    private final PaymentLedger ledger;
    private final CashLedger cash;
    private final DrawerSessionRepository sessions;

    public PaymentsQueryService(PaymentLedger ledger, CashLedger cash, DrawerSessionRepository sessions) {
        this.ledger = ledger;
        this.cash = cash;
        this.sessions = sessions;
    }

    /** A payment with its refunds. */
    public record PaymentDetail(Captured captured, List<Refund> refunds) {}

    /** A drawer session with its movements and expected cash. */
    public record SessionDetail(DrawerSession session, List<CashMovement> movements, ExpectedCash expected) {}

    @Transactional(readOnly = true)
    public PaymentDetail payment(UUID id) {
        Captured captured = ledger.find(id)
                .orElseThrow(() -> new NotFoundException(PaymentsErrorCode.PAYMENT_NOT_FOUND, Map.of("paymentId", id)));
        return new PaymentDetail(captured, ledger.refundsOf(id));
    }

    /** What a payment list is narrowed to; {@code null} means any. */
    public record PaymentSearch(
            @Nullable UUID billId,
            @Nullable UUID customerId,
            @Nullable PaymentMethod method,
            @Nullable LocalDate businessDate) {}

    @Transactional(readOnly = true)
    public PageResponse<Captured> payments(PaymentSearch search, PageQuery query) {
        PaymentLedger.Filter filter =
                new PaymentLedger.Filter(search.billId(), search.customerId(), search.method(), search.businessDate());
        return PageResponse.of(
                ledger.page(filter, before(query), query.fetchSize()),
                query,
                c -> List.of(c.payment().id().toString()));
    }

    /** Rule PAY-06: totals by method for the business date, optionally of one register. */
    @Transactional(readOnly = true)
    public List<MethodTotal> summary(LocalDate businessDate, @Nullable UUID registerId) {
        return ledger.totals(businessDate, registerId);
    }

    @Transactional(readOnly = true)
    public SessionDetail session(UUID id) {
        DrawerSession session = sessions.findById(id)
                .orElseThrow(() ->
                        new NotFoundException(PaymentsErrorCode.DRAWER_SESSION_NOT_FOUND, Map.of("sessionId", id)));
        List<CashMovement> movements = cash.ofSession(id);
        return new SessionDetail(session, movements, ExpectedCash.of(movements));
    }

    @Transactional(readOnly = true)
    public PageResponse<DrawerSession> sessions(
            @Nullable UUID registerId, @Nullable DrawerStatus status, PageQuery query) {
        return PageResponse.of(
                sessions.page(registerId, status, before(query), query.fetchSize()),
                query,
                s -> List.of(s.id().toString()));
    }

    private static @Nullable UUID before(PageQuery query) {
        if (query.cursor() == null) {
            return null;
        }
        try {
            return UUID.fromString(CursorCodec.decode(Objects.requireNonNull(query.cursor()), 1)
                    .getFirst());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
        }
    }
}
