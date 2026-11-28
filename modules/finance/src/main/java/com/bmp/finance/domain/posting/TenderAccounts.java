package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.shared.error.TechnicalException;
import java.util.Map;

/** The cash-type account of each payment method (posting-rules.md §2 {@code PaymentCaptured}, ADR-032 §3). */
public final class TenderAccounts {

    private TenderAccounts() {}

    public static SystemKey of(String method) {
        return switch (method) {
            case "CASH" -> SystemKey.CASH_DRAWER;
            case "CARD" -> SystemKey.CARD_CLEARING;
            case "BANK_TRANSFER", "MOBILE_WALLET" -> SystemKey.TRANSFER_CLEARING;
            case "CHEQUE" -> SystemKey.CHEQUES;
            default ->
                throw new TechnicalException(FinanceErrorCode.POSTING_RULE_MISSING, Map.of("method", method), null);
        };
    }
}
