package com.bmp.pricing.domain.service;

import com.bmp.shared.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A priced line, or why it has no price (PRC-05: a missing price is a line status, not an error for the basket).
 *
 * @param status PRICED, PRICE_NOT_FOUND or NOT_SELLABLE
 * @param unitPrice the list (or derived) price of one unit, before rules
 * @param amount quantity × price after rules, rounded; tax included for tax-inclusive lists
 * @param discount quantity × list price, rounded, minus {@code amount} (negative for markups)
 * @param net the amount without tax
 * @param tax the taxes of the line
 * @param lineTotal what the customer pays for the line
 * @param taxInclusive whether the list's prices include tax
 * @param priceListId the list the price came from (PRC-10)
 * @param priceListVersion that list's version (PRC-10)
 * @param source LIST, DERIVED or RULE:code of the last rule that changed the price (PRC-10)
 * @param appliedRules codes of the rules applied, in order
 * @param taxes each rate's tax
 */
public record ResolvedLine(
        @Nullable String lineId,
        UUID variantId,
        UUID packagingUnitId,
        BigDecimal qty,
        Status status,
        @Nullable Money unitPrice,
        @Nullable Money amount,
        @Nullable Money discount,
        @Nullable Money net,
        @Nullable Money tax,
        @Nullable Money lineTotal,
        @Nullable Boolean taxInclusive,
        @Nullable UUID priceListId,
        @Nullable Integer priceListVersion,
        @Nullable String source,
        List<String> appliedRules,
        List<LineTax> taxes) {

    public ResolvedLine {
        appliedRules = List.copyOf(appliedRules);
        taxes = List.copyOf(taxes);
    }

    static ResolvedLine unpriced(ResolutionRequest.Line line, Status status) {
        return new ResolvedLine(
                line.lineId(),
                line.variantId(),
                line.packagingUnitId(),
                line.qty(),
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    /** Line outcomes. */
    public enum Status {
        PRICED,
        PRICE_NOT_FOUND,
        NOT_SELLABLE
    }
}
