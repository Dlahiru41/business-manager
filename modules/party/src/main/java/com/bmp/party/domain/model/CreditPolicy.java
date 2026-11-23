package com.bmp.party.domain.model;

import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule PTY-03: whether a party may buy on credit, up to which balance (in the tenant currency), and in how many days
 * its invoices are due.
 */
@Embeddable
public record CreditPolicy(
        @Column(name = "credit_allowed", nullable = false) boolean creditAllowed,
        @Column(name = "credit_limit", nullable = false) BigDecimal creditLimit,

        @Column(name = "payment_terms_days", nullable = false)
        int paymentTermsDays) {

    public static final int MAX_TERMS_DAYS = 365;
    private static final int MONEY_SCALE = 4;

    public CreditPolicy {
        List<FieldError> errors = new ArrayList<>();
        if (creditLimit.signum() < 0) {
            errors.add(new FieldError("creditLimit", "RANGE", "must not be negative"));
        }
        if (paymentTermsDays < 0 || paymentTermsDays > MAX_TERMS_DAYS) {
            errors.add(new FieldError("paymentTermsDays", "RANGE", "must be between 0 and 365"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailure(errors);
        }
        creditLimit = creditLimit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** No credit, invoices due at once: the policy of a new party. */
    public static CreditPolicy none() {
        return new CreditPolicy(false, BigDecimal.ZERO, 0);
    }
}
