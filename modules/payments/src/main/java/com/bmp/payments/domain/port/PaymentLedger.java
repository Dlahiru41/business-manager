package com.bmp.payments.domain.port;

import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.model.PaymentMethod;
import com.bmp.payments.domain.model.Refund;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The {@code payment} and {@code refund} ledgers of the current tenant: rows are appended, never changed. */
public interface PaymentLedger {

    /** A payment with what was refunded of it so far. */
    record Captured(Payment payment, BigDecimal refunded) {

        public BigDecimal remaining() {
            return payment.amount().subtract(refunded).max(BigDecimal.ZERO);
        }
    }

    /** What a payment list is narrowed to; {@code null} means any. */
    record Filter(
            @Nullable UUID billId,
            @Nullable UUID customerId,
            @Nullable PaymentMethod method,
            @Nullable LocalDate businessDate) {}

    /** Rule PAY-06: payments and refunds of one method on a business date. */
    record MethodTotal(PaymentMethod method, long count, BigDecimal captured, BigDecimal refunded) {

        public BigDecimal net() {
            return captured.subtract(refunded);
        }
    }

    void append(Payment payment);

    void append(Refund refund);

    /** Serialises refunds of these payments until the transaction ends (the ledger rows are never updated). */
    void lock(List<UUID> paymentIds);

    Optional<Captured> find(UUID paymentId);

    /** The payment of a bill tender (PAY-01: one per tender). */
    Optional<Payment> findByTender(UUID tenderId);

    /** A bill's payments in capture order, each with what was refunded of it. */
    List<Captured> ofBill(UUID billId);

    boolean refundExists(UUID refundId);

    List<Refund> refundsOf(UUID paymentId);

    /** Newest first, from before {@code beforeId}. */
    List<Captured> page(Filter filter, @Nullable UUID beforeId, int limit);

    /** Payments captured and refunds given on the business date, by method; optionally one register's. */
    List<MethodTotal> totals(LocalDate businessDate, @Nullable UUID registerId);
}
