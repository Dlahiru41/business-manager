package com.bmp.sales.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A discount on the whole bill, allocated across the lines (SAL-04).
 *
 * @param value an amount, or a percentage 0..100
 * @param approvedBy the manager who allowed it above the cashier's limit (SAL-05)
 */
@Embeddable
public record BillDiscount(
        @Enumerated(EnumType.STRING) @Column(name = "discount_type")
        DiscountType type,

        @Column(name = "discount_value") BigDecimal value,
        @Column(name = "discount_reason") @Nullable String reason,
        @Column(name = "discount_approved_by") @Nullable UUID approvedBy) {}
