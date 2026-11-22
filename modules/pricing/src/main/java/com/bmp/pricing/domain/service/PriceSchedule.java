package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceChangeKind;
import com.bmp.pricing.domain.model.PriceKey;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Plans a bulk price upsert into one list version (PRC-02, PRC-03), validating every row before anything changes:
 *
 * <ul>
 *   <li>a row without {@code validFrom} is the price from now on: the open price of the same key ends now (or, when it
 *       started at this very instant, is changed);
 *   <li>a row with the {@code validFrom} of an existing price of the same key changes that price;
 *   <li>any other overlap with a window of the same key is {@code PRICE_OVERLAP}.
 * </ul>
 */
public final class PriceSchedule {

    private PriceSchedule() {}

    /**
     * A requested price.
     *
     * @param key variant, packaging unit and minimum quantity
     * @param amount the price, not negative
     * @param validFrom when it starts, or {@code null} for now
     * @param validTo when it ends (exclusive), or {@code null} for open-ended
     */
    public record Row(
            PriceKey key,
            BigDecimal amount,
            @Nullable Instant validFrom,
            @Nullable Instant validTo) {}

    /**
     * What happened to a price.
     *
     * @param kind created, updated or ended
     * @param price the price after the change
     * @param previousAmount the amount before an update
     * @param previousValidTo the end before an update or ending
     */
    public record Change(
            PriceChangeKind kind,
            Price price,
            @Nullable BigDecimal previousAmount,
            @Nullable Instant previousValidTo) {}

    /**
     * Applies the rows to the prices of the list.
     *
     * @param existing the list's prices for the keys of the rows
     * @param listVersion the list version the changes belong to
     */
    public static List<Change> apply(
            UUID tenantId,
            UUID priceListId,
            List<Row> rows,
            List<Price> existing,
            Instant now,
            int listVersion,
            Supplier<UUID> ids) {
        requireDistinct(rows);
        List<Price> windows = new ArrayList<>(existing);
        List<Change> changes = new ArrayList<>();
        for (Row row : rows) {
            Instant from = row.validFrom() != null ? row.validFrom() : now;
            if (row.validTo() != null && !row.validTo().isAfter(from)) {
                throw new BusinessRuleViolation(
                        PricingErrorCode.INVALID_VALIDITY,
                        Map.of(
                                "validFrom",
                                from.toString(),
                                "validTo",
                                row.validTo().toString()));
            }
            Optional<Price> same = windows.stream()
                    .filter(p -> p.key().equals(row.key()) && p.validFrom().equals(from))
                    .findFirst();
            if (same.isPresent()) {
                Price price = same.get();
                BigDecimal previousAmount = price.amount();
                Instant previousTo = price.validTo();
                requireFree(windows, row.key(), from, row.validTo(), price);
                price.change(row.amount(), row.validTo(), listVersion);
                changes.add(new Change(PriceChangeKind.UPDATED, price, previousAmount, previousTo));
                continue;
            }
            Price ended = null;
            if (row.validFrom() == null) {
                Optional<Price> open = windows.stream()
                        .filter(p -> p.key().equals(row.key()) && p.validAt(now))
                        .findFirst();
                if (open.isPresent()) {
                    ended = open.get();
                    Instant previousTo = ended.validTo();
                    ended.endAt(now, listVersion);
                    changes.add(new Change(PriceChangeKind.ENDED, ended, null, previousTo));
                }
            }
            requireFree(windows, row.key(), from, row.validTo(), ended);
            Price created = Price.create(
                    ids.get(), tenantId, priceListId, row.key(), row.amount(), from, row.validTo(), listVersion);
            windows.add(created);
            changes.add(new Change(PriceChangeKind.CREATED, created, null, null));
        }
        return changes;
    }

    private static void requireDistinct(List<Row> rows) {
        Set<List<Object>> seen = new HashSet<>();
        for (Row row : rows) {
            List<Object> identity = new ArrayList<>();
            identity.add(row.key());
            identity.add(Objects.requireNonNullElse(row.validFrom(), "now"));
            if (!seen.add(identity)) {
                throw new BusinessRuleViolation(
                        PricingErrorCode.DUPLICATE_PRICE_ROWS,
                        Map.of(
                                "packagingUnitId",
                                row.key().packagingUnitId(),
                                "minQty",
                                row.key().minQty()));
            }
        }
    }

    /** Rule PRC-02: no other window of the key overlaps {@code [from, to)}. */
    private static void requireFree(
            List<Price> windows, PriceKey key, Instant from, @Nullable Instant to, @Nullable Price ignored) {
        for (Price price : windows) {
            boolean skipped = ignored != null && price.id().equals(ignored.id());
            if (!skipped && price.key().equals(key) && price.overlaps(from, to)) {
                throw new BusinessRuleViolation(
                        PricingErrorCode.PRICE_OVERLAP,
                        Map.of(
                                "packagingUnitId", key.packagingUnitId(),
                                "minQty", key.minQty().toPlainString(),
                                "validFrom", price.validFrom().toString()));
            }
        }
    }
}
