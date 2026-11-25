package com.bmp.sales.domain.service;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.Mode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Rule SAL-07: a credit sale keeps the customer's exposure (what they owe, from the {@code CreditExposureProvider})
 * plus the new credit within their limit. Online it is refused above the limit unless approved
 * ({@code sales:credit-override}); offline it is accepted and flagged.
 */
public final class CreditCheck {

    private CreditCheck() {}

    public static Optional<BillFlag> check(
            BigDecimal exposure, BigDecimal credit, BigDecimal limit, boolean overrideApproved, Mode mode) {
        if (credit.signum() == 0 || overrideApproved || exposure.add(credit).compareTo(limit) <= 0) {
            return Optional.empty();
        }
        if (mode.online()) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.CREDIT_LIMIT_EXCEEDED,
                    Map.of(
                            "exposure", exposure.toPlainString(),
                            "credit", credit.toPlainString(),
                            "creditLimit", limit.toPlainString()));
        }
        return Optional.of(BillFlag.CREDIT_LIMIT_EXCEEDED);
    }
}
