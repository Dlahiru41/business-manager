package com.bmp.payments.adapter.in.web;

import com.bmp.payments.adapter.in.web.generated.api.PaymentsApi;
import com.bmp.payments.adapter.in.web.generated.model.CapturePaymentRequest;
import com.bmp.payments.adapter.in.web.generated.model.Payment;
import com.bmp.payments.adapter.in.web.generated.model.PaymentMethod;
import com.bmp.payments.adapter.in.web.generated.model.PaymentPage;
import com.bmp.payments.adapter.in.web.generated.model.PaymentSummary;
import com.bmp.payments.adapter.in.web.generated.model.Refund;
import com.bmp.payments.adapter.in.web.generated.model.RefundRequest;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.application.PaymentsSettings;
import com.bmp.payments.application.command.PaymentHandlers;
import com.bmp.payments.application.query.PaymentsQueryService;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Payments, account collections and refunds (PAY-02, PAY-03, PAY-06). */
@RestController
class PaymentsController implements PaymentsApi {

    private final PaymentHandlers handlers;
    private final PaymentsQueryService queries;
    private final PaymentsSettings settings;
    private final PaymentsWebMapper mapper;

    PaymentsController(
            PaymentHandlers handlers,
            PaymentsQueryService queries,
            PaymentsSettings settings,
            PaymentsWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'payments:read')")
    public ResponseEntity<PaymentPage> listPayments(
            @Nullable UUID billId,
            @Nullable UUID customerId,
            @Nullable PaymentMethod method,
            @Nullable LocalDate businessDate,
            Integer limit,
            @Nullable String cursor) {
        var page = queries.payments(
                new PaymentsQueryService.PaymentSearch(
                        billId, customerId, method == null ? null : PaymentsWebMapper.method(method), businessDate),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PaymentPage()
                .items(page.items().stream().map(mapper::payment).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'payments:capture')")
    public ResponseEntity<Payment> capturePayment(UUID idempotencyKey, CapturePaymentRequest request) {
        var payment = handlers.collect(new PaymentHandlers.Collect(
                request.getId(),
                request.getCustomerId(),
                PaymentsWebMapper.method(request.getMethod()),
                new BigDecimal(request.getAmount()),
                PaymentsWebMapper.decimalOrNull(request.getTendered()),
                request.getReference(),
                request.getRegisterId(),
                request.getAllocations() == null
                        ? List.of()
                        : request.getAllocations().stream()
                                .map(a -> new PaymentCaptured.Allocation(a.getBillId(), new BigDecimal(a.getAmount())))
                                .toList()));
        return ResponseEntity.created(URI.create("/api/v1/payments/" + payment.id()))
                .body(mapper.payment(queries.payment(payment.id()).captured()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'payments:read')")
    public ResponseEntity<PaymentSummary> getPaymentSummary(LocalDate date, @Nullable UUID registerId) {
        return ResponseEntity.ok(
                mapper.summary(date, registerId, queries.summary(date, registerId), settings.currency()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'payments:read')")
    public ResponseEntity<Payment> getPayment(UUID paymentId) {
        return ResponseEntity.ok(mapper.payment(queries.payment(paymentId).captured()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'payments:refund')")
    public ResponseEntity<Refund> refundPayment(UUID paymentId, UUID idempotencyKey, RefundRequest request) {
        var refund = handlers.refund(
                paymentId,
                new PaymentHandlers.RefundCommand(
                        request.getId(),
                        new BigDecimal(request.getAmount()),
                        request.getReason(),
                        request.getMethod() == null ? null : PaymentsWebMapper.method(request.getMethod()),
                        request.getRegisterId()));
        return ResponseEntity.created(URI.create("/api/v1/payments/" + paymentId))
                .body(mapper.refund(refund));
    }
}
