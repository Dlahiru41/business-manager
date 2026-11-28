package com.bmp.finance.application;

import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.port.PartyAccounts;
import com.bmp.sales.api.spi.CreditExposureProvider;
import com.bmp.shared.id.PartyId;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule FIN-10: a customer's credit exposure is what they owe on account — Σ open receivables, never below zero — so a
 * collection raises the credit sales may still give. Replaces sales' fallback (SAL-07) without a change to sales.
 */
@Component
class FinanceCreditExposure implements CreditExposureProvider {

    private final PartyAccounts accounts;

    FinanceCreditExposure(PartyAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal exposureOf(PartyId customer) {
        return accounts.balance(PartyRef.customer(customer.value()))
                .orElse(BigDecimal.ZERO)
                .max(BigDecimal.ZERO);
    }
}
