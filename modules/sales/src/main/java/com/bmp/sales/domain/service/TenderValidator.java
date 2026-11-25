package com.bmp.sales.domain.service;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.Tender;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.security.CardNumbers;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Rules SAL-06, SAL-07, PAY-05: the tenders add up to the grand total exactly; cash covers its amount and the rest is
 * change; CARD and BANK_TRANSFER carry a reference (unless the tenant relaxes it); CREDIT needs a customer allowed
 * credit; nothing looks like a card number. Online every breach is refused; offline the bill already happened, so a
 * sum that does not match is flagged ({@code TENDER_MISMATCH}) and a customer not allowed credit is flagged
 * ({@code CREDIT_LIMIT_EXCEEDED}); card data and credit without any customer are refused in both modes.
 */
public final class TenderValidator {

    private TenderValidator() {}

    /**
     * What the tenders are checked against.
     *
     * @param referenceRequired CARD and BANK_TRANSFER need a reference
     * @param customerId the bill's customer, if any
     * @param creditAllowed whether that customer may buy on credit
     */
    public record Rules(boolean referenceRequired, @Nullable UUID customerId, boolean creditAllowed) {}

    /** The flags an offline bill gets; throws when the tenders are refused. */
    public static Set<BillFlag> validate(List<Tender> tenders, BigDecimal grandTotal, Rules rules, Mode mode) {
        Set<BillFlag> flags = EnumSet.noneOf(BillFlag.class);
        if (tenders.isEmpty()) {
            throw refuse(SalesErrorCode.TENDER_INVALID, "A completed bill has at least one tender", null);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Tender tender : tenders) {
            if (CardNumbers.containsCardNumber(tender.reference())) {
                throw refuse(SalesErrorCode.CARD_DATA_NOT_ALLOWED, null, tender.id());
            }
            if (tender.amount().signum() <= 0) {
                throw refuse(SalesErrorCode.TENDER_INVALID, "A tender amount is positive", tender.id());
            }
            switch (tender) {
                case Tender.Cash cash
                when cash.change().signum() < 0 && mode.online() ->
                    throw refuse(SalesErrorCode.TENDER_INVALID, "Cash tendered covers the amount", tender.id());
                case Tender.NonCash card
                when rules.referenceRequired()
                        && card.method().needsReference()
                        && (card.reference() == null || card.reference().isBlank())
                        && mode.online() ->
                    throw refuse(SalesErrorCode.TENDER_INVALID, "A card or transfer needs its reference", tender.id());
                case Tender.Credit credit -> checkCredit(credit, rules, mode, flags);
                default -> {
                    // Valid as it is.
                }
            }
            sum = sum.add(tender.amount());
        }
        if (sum.compareTo(grandTotal) != 0) {
            if (mode.online()) {
                throw new BusinessRuleViolation(
                        SalesErrorCode.TENDER_MISMATCH,
                        Map.of("tendered", sum.toPlainString(), "grandTotal", grandTotal.toPlainString()));
            }
            flags.add(BillFlag.TENDER_MISMATCH);
        }
        return flags;
    }

    private static void checkCredit(Tender.Credit credit, Rules rules, Mode mode, Set<BillFlag> flags) {
        if (rules.customerId() == null) {
            throw refuse(SalesErrorCode.CREDIT_NOT_ALLOWED, "A credit sale needs a customer", credit.id());
        }
        if (!rules.creditAllowed()) {
            if (mode.online()) {
                throw refuse(SalesErrorCode.CREDIT_NOT_ALLOWED, null, credit.id());
            }
            flags.add(BillFlag.CREDIT_LIMIT_EXCEEDED);
        }
    }

    private static BusinessRuleViolation refuse(ErrorCode code, @Nullable String message, @Nullable UUID tenderId) {
        return tenderId == null
                ? new BusinessRuleViolation(code, message, Map.of())
                : new BusinessRuleViolation(code, message, Map.of("tenderId", tenderId));
    }
}
