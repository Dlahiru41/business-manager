package com.bmp.pricing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The amount of one packaging unit of a variant in one price list, from a minimum quantity, during a validity window
 * {@code [validFrom, validTo)} (PRC-02). Prices are never deleted: a new price ends the open one (PRC-03 keeps the
 * history in the list versions).
 */
@Entity
@Table(schema = "pricing", name = "price")
public class Price {

    /** Scale of {@code amount} (numeric(19,4)). */
    public static final int AMOUNT_SCALE = 4;

    /** Scale of {@code min_qty} (numeric(20,6)). */
    public static final int QTY_SCALE = 6;

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "price_list_id", nullable = false, updatable = false)
    private UUID priceListId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "packaging_unit_id", nullable = false, updatable = false)
    private UUID packagingUnitId;

    @Column(name = "min_qty", nullable = false, updatable = false, precision = 20, scale = 6)
    private BigDecimal minQty;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    @Column(name = "list_version", nullable = false)
    private int listVersion;

    @Version
    private @Nullable Long version;

    @SuppressWarnings("NullAway.Init") // JPA
    protected Price() {}

    private Price(
            UUID id,
            UUID tenantId,
            UUID priceListId,
            PriceKey key,
            BigDecimal amount,
            Instant validFrom,
            @Nullable Instant validTo,
            int listVersion) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.priceListId = Objects.requireNonNull(priceListId, "priceListId");
        this.variantId = key.variantId();
        this.packagingUnitId = key.packagingUnitId();
        this.minQty = key.minQty();
        this.amount = scaled(amount);
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.validTo = validTo;
        this.listVersion = listVersion;
    }

    public static Price create(
            UUID id,
            UUID tenantId,
            UUID priceListId,
            PriceKey key,
            BigDecimal amount,
            Instant validFrom,
            @Nullable Instant validTo,
            int listVersion) {
        return new Price(id, tenantId, priceListId, key, amount, validFrom, validTo, listVersion);
    }

    /** Ends the price at {@code at} (exclusive), as part of list version {@code listVersion}. */
    public void endAt(Instant at, int newListVersion) {
        this.validTo = at;
        this.listVersion = newListVersion;
    }

    /** Changes the amount and end of a price that keeps its start (a scheduled price corrected). */
    public void change(BigDecimal newAmount, @Nullable Instant newValidTo, int newListVersion) {
        this.amount = scaled(newAmount);
        this.validTo = newValidTo;
        this.listVersion = newListVersion;
    }

    /** Validity is inclusive at the start and exclusive at the end. */
    public boolean validAt(Instant at) {
        return !validFrom.isAfter(at) && (validTo == null || at.isBefore(validTo));
    }

    /** Whether {@code [from, to)} overlaps this price's window ({@code null} ends are open). */
    public boolean overlaps(Instant from, @Nullable Instant to) {
        boolean startsBeforeOtherEnds = to == null || validFrom.isBefore(to);
        boolean otherStartsBeforeThisEnds = validTo == null || from.isBefore(validTo);
        return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }

    public PriceKey key() {
        return new PriceKey(variantId, packagingUnitId, minQty);
    }

    public UUID id() {
        return id;
    }

    public UUID priceListId() {
        return priceListId;
    }

    public UUID variantId() {
        return variantId;
    }

    public UUID packagingUnitId() {
        return packagingUnitId;
    }

    public BigDecimal minQty() {
        return minQty;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public @Nullable Instant validTo() {
        return validTo;
    }

    public int listVersion() {
        return listVersion;
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }
}
