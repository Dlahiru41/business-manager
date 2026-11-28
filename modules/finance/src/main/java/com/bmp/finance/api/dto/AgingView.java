package com.bmp.finance.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Open amounts per party in the buckets 0–30, 31–60, 61–90 and 90+ days past due (FIN-07). */
public record AgingView(String currency, List<Row> rows, Buckets totals) {

    public AgingView {
        rows = List.copyOf(rows);
    }

    /** A party's balance and buckets. */
    public record Row(UUID partyId, BigDecimal balance, Buckets buckets) {}

    /** The four buckets; their sum is the balance. */
    public record Buckets(BigDecimal days0To30, BigDecimal days31To60, BigDecimal days61To90, BigDecimal days90Plus) {

        public BigDecimal total() {
            return days0To30.add(days31To60).add(days61To90).add(days90Plus);
        }
    }
}
