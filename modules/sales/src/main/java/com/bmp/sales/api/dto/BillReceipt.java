package com.bmp.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A completed bill as a receipt shows it (DOC-02).
 *
 * @param grandTotal what the customer paid (the till's total for an offline bill that differs, SAL-04)
 * @param customer the customer's display name, on credit bills only
 */
public record BillReceipt(
        UUID billId,
        String number,
        UUID locationId,
        String registerCode,
        Instant completedAt,
        List<Line> lines,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        List<Tender> tenders,
        @Nullable String customer,
        String currency) {

    public BillReceipt {
        lines = List.copyOf(lines);
        tenders = List.copyOf(tenders);
    }

    /** A line; {@code unit} is the packaging unit's name. */
    public record Line(
            String name,
            BigDecimal qty,
            String unit,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal discount) {}

    /** A tender; {@code change} is the cash given back. */
    public record Tender(String method, BigDecimal amount, BigDecimal change) {}
}
