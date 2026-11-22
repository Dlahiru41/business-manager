package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.model.SalesChannel;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import java.time.Instant;
import java.util.List;

/**
 * A priced basket; totals are sums of the rounded priced lines (02 par. 5).
 *
 * @param net sum of the lines without tax
 * @param tax sum of the line taxes
 * @param discount sum of the line discounts
 * @param lineTotal sum of what the customer pays
 */
public record ResolvedBasket(
        SalesChannel channel,
        CurrencyCode currency,
        Instant at,
        List<ResolvedLine> lines,
        Money net,
        Money tax,
        Money discount,
        Money lineTotal) {

    public ResolvedBasket {
        lines = List.copyOf(lines);
    }
}
