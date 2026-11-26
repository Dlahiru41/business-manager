package com.bmp.payments.adapter.in.web;

import com.bmp.payments.adapter.in.web.generated.model.CashMovementType;
import com.bmp.payments.adapter.in.web.generated.model.Denomination;
import com.bmp.payments.adapter.in.web.generated.model.DrawerStatus;
import com.bmp.payments.adapter.in.web.generated.model.ExpectedCash;
import com.bmp.payments.adapter.in.web.generated.model.MethodTotal;
import com.bmp.payments.adapter.in.web.generated.model.PaymentAmount;
import com.bmp.payments.adapter.in.web.generated.model.PaymentKind;
import com.bmp.payments.adapter.in.web.generated.model.PaymentMethod;
import com.bmp.payments.adapter.in.web.generated.model.PaymentPagePage;
import com.bmp.payments.adapter.in.web.generated.model.PaymentSummary;
import com.bmp.payments.application.query.PaymentsQueryService.SessionDetail;
import com.bmp.payments.domain.model.CashMovement;
import com.bmp.payments.domain.model.DenominationCount;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.model.Refund;
import com.bmp.payments.domain.port.PaymentLedger;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;

/** Payments and drawers to API representations: decimals are strings (02 §5). */
@Mapper(componentModel = "spring")
interface PaymentsWebMapper {

    default com.bmp.payments.adapter.in.web.generated.model.Payment payment(PaymentLedger.Captured captured) {
        Payment p = captured.payment();
        String currency = p.currency();
        return new com.bmp.payments.adapter.in.web.generated.model.Payment()
                .id(p.id())
                .kind(PaymentKind.fromValue(p.kind().name()))
                .billId(p.billId())
                .tenderId(p.tenderId())
                .customerId(p.customerId())
                .method(method(p.method()))
                .amount(money(p.amount(), currency))
                .tendered(p.tendered() == null ? null : money(p.tendered(), currency))
                .changeGiven(p.changeGiven() == null ? null : money(p.changeGiven(), currency))
                .refunded(money(captured.refunded(), currency))
                .reference(p.reference())
                .registerId(p.registerId())
                .drawerSessionId(p.drawerSessionId())
                .deviceId(p.deviceId())
                .currency(currency)
                .capturedAt(utc(p.capturedAt()));
    }

    default com.bmp.payments.adapter.in.web.generated.model.Refund refund(Refund r) {
        return new com.bmp.payments.adapter.in.web.generated.model.Refund()
                .id(r.id())
                .paymentId(r.paymentId())
                .amount(money(r.amount(), r.currency()))
                .method(method(r.method()))
                .reason(r.reason())
                .drawerSessionId(r.drawerSessionId())
                .creditNoteId(r.creditNoteId())
                .createdAt(utc(r.createdAt()));
    }

    default PaymentSummary summary(
            LocalDate date, @Nullable UUID registerId, List<PaymentLedger.MethodTotal> totals, String currency) {
        BigDecimal net = totals.stream().map(PaymentLedger.MethodTotal::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentSummary()
                .businessDate(date)
                .registerId(registerId)
                .byMethod(totals.stream()
                        .map(t -> new MethodTotal()
                                .method(method(t.method()))
                                .count(Math.toIntExact(t.count()))
                                .captured(money(t.captured(), currency))
                                .refunded(money(t.refunded(), currency))
                                .net(money(t.net(), currency)))
                        .toList())
                .net(money(net, currency));
    }

    default com.bmp.payments.adapter.in.web.generated.model.DrawerSession session(DrawerSession s) {
        return session(s, null);
    }

    default com.bmp.payments.adapter.in.web.generated.model.DrawerSession session(SessionDetail detail) {
        return session(detail.session(), detail.movements());
    }

    default com.bmp.payments.adapter.in.web.generated.model.DrawerSession session(
            DrawerSession s, @Nullable List<CashMovement> movements) {
        String currency = s.currency();
        return new com.bmp.payments.adapter.in.web.generated.model.DrawerSession()
                .id(s.id())
                .registerId(s.registerId())
                .status(DrawerStatus.fromValue(s.status().name()))
                .openedBy(s.openedByUser())
                .openingFloat(money(s.openingFloat(), currency))
                .expectedCash(s.expectedCash() == null ? null : money(s.expectedCash(), currency))
                .countedCash(s.countedCash() == null ? null : money(s.countedCash(), currency))
                .variance(s.variance() == null ? null : money(s.variance(), currency))
                .denominations(
                        s.denominations().isEmpty()
                                ? null
                                : s.denominations().stream()
                                        .map(this::denomination)
                                        .toList())
                .movements(
                        movements == null
                                ? null
                                : movements.stream()
                                        .map(m -> movement(m, currency))
                                        .toList())
                .openedAt(utc(s.openedAt()))
                .countedAt(time(s.countedAt()))
                .reconciledAt(time(s.reconciledAt()))
                .reconciledBy(s.reconciledBy())
                .version(s.version());
    }

    default ExpectedCash expected(SessionDetail detail) {
        String currency = detail.session().currency();
        var e = detail.expected();
        return new ExpectedCash()
                .sessionId(detail.session().id())
                .openingFloat(money(e.openingFloat(), currency))
                .sales(money(e.sales(), currency))
                .refunds(money(e.refunds(), currency))
                .paidOut(money(e.paidOut(), currency))
                .paidIn(money(e.paidIn(), currency))
                .drops(money(e.drops(), currency))
                .expected(money(e.expected(), currency));
    }

    default com.bmp.payments.adapter.in.web.generated.model.CashMovement movement(CashMovement m, String currency) {
        return new com.bmp.payments.adapter.in.web.generated.model.CashMovement()
                .id(m.id())
                .type(CashMovementType.fromValue(m.type().name()))
                .amount(money(m.amount(), currency))
                .reason(m.reason())
                .sourceType(m.sourceType())
                .sourceId(m.sourceId())
                .occurredAt(utc(m.occurredAt()));
    }

    default Denomination denomination(DenominationCount d) {
        return new Denomination().value(decimal(d.value())).count(d.count());
    }

    default DenominationCount denomination(Denomination d) {
        return new DenominationCount(new BigDecimal(d.getValue()), d.getCount());
    }

    default PaymentPagePage page(PageResponse<?> page) {
        return new PaymentPagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }

    default PaymentMethod method(com.bmp.payments.domain.model.PaymentMethod method) {
        return PaymentMethod.fromValue(method.name());
    }

    static com.bmp.payments.domain.model.PaymentMethod method(PaymentMethod method) {
        return com.bmp.payments.domain.model.PaymentMethod.valueOf(method.getValue());
    }

    default PaymentAmount money(BigDecimal amount, String currency) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return new PaymentAmount()
                .amount(stripped.setScale(Math.max(2, stripped.scale()), RoundingMode.UNNECESSARY)
                        .toPlainString())
                .currency(currency);
    }

    default String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    static @Nullable BigDecimal decimalOrNull(@Nullable String value) {
        return value == null ? null : new BigDecimal(value);
    }

    default OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    default @Nullable OffsetDateTime time(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
