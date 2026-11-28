package com.bmp.finance.domain.service;

import com.bmp.finance.domain.model.OpenItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Rule FIN-07: open amounts by days past the due date — 0–30 (not yet due included), 31–60, 61–90 and over 90.
 * Credits not yet applied count in the first bucket, so the buckets always add up to the balance.
 */
public final class Aging {

    private static final int FIRST = 30;
    private static final int SECOND = 60;
    private static final int THIRD = 90;

    private Aging() {}

    /** The four buckets. */
    public record Buckets(BigDecimal days0To30, BigDecimal days31To60, BigDecimal days61To90, BigDecimal days90Plus) {

        public static final Buckets ZERO =
                new Buckets(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public BigDecimal total() {
            return days0To30.add(days31To60).add(days61To90).add(days90Plus);
        }

        public Buckets plus(Buckets other) {
            return new Buckets(
                    days0To30.add(other.days0To30),
                    days31To60.add(other.days31To60),
                    days61To90.add(other.days61To90),
                    days90Plus.add(other.days90Plus));
        }
    }

    public static Buckets of(List<OpenItem> items, LocalDate asOf) {
        Buckets buckets = Buckets.ZERO;
        for (OpenItem item : items) {
            buckets = buckets.plus(bucket(item.remaining(), item.isDebit() ? item.daysOverdue(asOf) : 0));
        }
        return buckets;
    }

    /** One amount, {@code daysOverdue} days past due. */
    public static Buckets bucket(BigDecimal amount, long daysOverdue) {
        BigDecimal zero = BigDecimal.ZERO;
        if (daysOverdue <= FIRST) {
            return new Buckets(amount, zero, zero, zero);
        }
        if (daysOverdue <= SECOND) {
            return new Buckets(zero, amount, zero, zero);
        }
        if (daysOverdue <= THIRD) {
            return new Buckets(zero, zero, amount, zero);
        }
        return new Buckets(zero, zero, zero, amount);
    }
}
