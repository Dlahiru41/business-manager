package com.bmp.sales.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How part of a credit note is paid back (SAL-10); CREDIT reduces what the customer owes.
 *
 * @param refundId the refund payments records for it, so redelivery refunds once
 * @param originalTenderId the bill tender it pays back, when there was only one method
 */
@Embeddable
public record RefundTender(
        @Column(name = "refund_id", nullable = false) UUID refundId,

        @Enumerated(EnumType.STRING) @Column(name = "method", nullable = false)
        TenderMethod method,

        @Column(name = "amount", nullable = false) BigDecimal amount,
        @Column(name = "reference") @Nullable String reference,
        @Column(name = "original_tender_id") @Nullable UUID originalTenderId) {}
