package com.bmp.payments.domain.service;

import com.bmp.payments.domain.model.PaymentMethod;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Rule PAY-03: a refund of a bill is taken from its payments, those of the refund's method first, each up to what is
 * left of it after earlier refunds ({@code captured − refunded}); nothing is ever refunded beyond a payment.
 */
public final class RefundAllocation {

    private RefundAllocation() {}

    /** A payment and what was refunded of it so far, in capture order. */
    public record Balance(UUID paymentId, PaymentMethod method, BigDecimal amount, BigDecimal refunded) {

        public BigDecimal remaining() {
            return amount.subtract(refunded).max(BigDecimal.ZERO);
        }
    }

    /** A part of the refund taken from one payment. */
    public record Part(UUID paymentId, BigDecimal amount) {}

    /** The parts, and what could not be taken from any payment. */
    public record Plan(List<Part> parts, BigDecimal unallocated) {}

    public static Plan allocate(BigDecimal amount, PaymentMethod method, List<Balance> payments) {
        List<Balance> ordered = new ArrayList<>(payments);
        ordered.sort(Comparator.comparingInt((Balance b) -> b.method() == method ? 0 : 1));
        List<Part> parts = new ArrayList<>();
        BigDecimal left = amount;
        for (Balance payment : ordered) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = left.min(payment.remaining());
            if (take.signum() > 0) {
                parts.add(new Part(payment.paymentId(), take));
                left = left.subtract(take);
            }
        }
        return new Plan(parts, left.max(BigDecimal.ZERO));
    }
}
