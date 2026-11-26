package com.bmp.receipt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a receipt says (DOC-02): one bill, as documents or a till's print agent builds it.
 *
 * @param copy a reprint: the receipt carries a COPY banner
 * @param cashTendered whether cash was tendered, which opens the drawer when the template says so (DOC-04)
 */
public record ReceiptModel(
        UUID billId,
        String billNumber,
        Instant issuedAt,
        @Nullable String cashier,
        @Nullable String register,
        List<Line> lines,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        List<Tender> tenders,
        BigDecimal change,
        @Nullable String customer,
        String currency,
        boolean copy,
        boolean cashTendered) {

    public ReceiptModel {
        Objects.requireNonNull(billId, "billId");
        Objects.requireNonNull(billNumber, "billNumber");
        lines = List.copyOf(lines);
        tenders = List.copyOf(tenders);
    }

    /**
     * A bill line.
     *
     * @param qty in the unit sold, e.g. {@code 10} pieces or {@code 0.350} kg
     * @param discount taken off this line; zero when none
     */
    public record Line(
            String name,
            BigDecimal qty,
            String unit,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal discount) {}

    /** A tender, e.g. {@code CASH 1,000.00}. */
    public record Tender(String method, BigDecimal amount) {}

    /** The same receipt as a reprint. */
    public ReceiptModel asCopy() {
        return new ReceiptModel(
                billId,
                billNumber,
                issuedAt,
                cashier,
                register,
                lines,
                discountTotal,
                taxTotal,
                total,
                tenders,
                change,
                customer,
                currency,
                true,
                cashTendered);
    }
}
