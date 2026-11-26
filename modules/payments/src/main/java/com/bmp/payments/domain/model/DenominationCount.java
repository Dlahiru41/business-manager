package com.bmp.payments.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** How many notes or coins of one value were counted in a drawer (PAY-04). */
@Embeddable
public record DenominationCount(
        @Column(name = "value", nullable = false) BigDecimal value,
        @Column(name = "count", nullable = false) int count) {

    public BigDecimal total() {
        return value.multiply(BigDecimal.valueOf(count));
    }
}
