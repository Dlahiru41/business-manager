package com.bmp.inventory.domain.model;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A stock count of one location (INV-12): OPEN while lines are counted, SUBMITTED for review, POSTED once its
 * variances are booked (never changed afterwards) or CANCELLED.
 */
@Entity
@Table(schema = "inventory", name = "stock_count")
public class StockCount {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CountStatus status;

    private @Nullable String note;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "variant_ids", nullable = false, updatable = false)
    private UUID[] variantIds;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "count_id", nullable = false, updatable = false)
    @OrderBy("snapshotAt, id")
    private List<StockCountLine> lines = new ArrayList<>();

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "submitted_at")
    private @Nullable Instant submittedAt;

    @Column(name = "posted_at")
    private @Nullable Instant postedAt;

    @Column(name = "posted_by")
    private @Nullable UUID postedBy;

    @Version
    private @Nullable Long version;

    @SuppressWarnings("NullAway.Init") // JPA
    protected StockCount() {}

    private StockCount(
            UUID id,
            TenantId tenant,
            UUID locationId,
            List<UUID> variantIds,
            @Nullable String note,
            Instant startedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.locationId = Objects.requireNonNull(locationId, "locationId");
        this.variantIds = variantIds.toArray(UUID[]::new);
        this.note = note;
        this.startedAt = startedAt;
        this.status = CountStatus.OPEN;
    }

    /** Starts an OPEN count; an empty scope counts everything. */
    public static StockCount open(
            UUID id, TenantId tenant, UUID locationId, List<UUID> variantIds, @Nullable String note, Instant at) {
        return new StockCount(id, tenant, locationId, variantIds, note, at);
    }

    /**
     * Records a counted quantity; the first time a line is counted it takes the system quantity as its snapshot.
     *
     * @param systemQty the balance now, asked only for a new line
     */
    public void record(
            BalanceKey key,
            BigDecimal counted,
            @Nullable String reason,
            Supplier<BigDecimal> systemQty,
            Supplier<UUID> ids,
            Instant at) {
        require(CountStatus.OPEN);
        if (variantIds.length > 0 && !List.of(variantIds).contains(key.variantId())) {
            throw new BusinessRuleViolation(
                    InventoryErrorCode.UNKNOWN_VARIANT,
                    "The variant is outside the count",
                    Map.of("variantId", key.variantId()));
        }
        for (StockCountLine line : lines) {
            if (line.counts(key.variantId(), key.stockState(), key.batchId())) {
                line.recount(counted, reason);
                return;
            }
        }
        lines.add(new StockCountLine(ids.get(), tenantId, key, systemQty.get(), counted, reason, at));
    }

    public void submit(Instant at) {
        require(CountStatus.OPEN);
        this.status = CountStatus.SUBMITTED;
        this.submittedAt = at;
    }

    /**
     * Posts the count; the caller books a COUNT_ADJ movement for each line with a variance and passes its cost.
     *
     * @param costs the cost of each line's variance movement, by line id
     */
    public void post(Map<UUID, BigDecimal> costs, Instant at, @Nullable UUID by) {
        require(CountStatus.SUBMITTED);
        for (StockCountLine line : lines) {
            line.posted(costs.getOrDefault(line.id(), BigDecimal.ZERO));
        }
        this.status = CountStatus.POSTED;
        this.postedAt = at;
        this.postedBy = by;
    }

    public void cancel() {
        if (status == CountStatus.CANCELLED) {
            return;
        }
        if (status == CountStatus.POSTED) {
            throw new BusinessRuleViolation(InventoryErrorCode.COUNT_ALREADY_POSTED, Map.of("countId", id));
        }
        this.status = CountStatus.CANCELLED;
    }

    private void require(CountStatus expected) {
        if (status == CountStatus.POSTED) {
            throw new BusinessRuleViolation(InventoryErrorCode.COUNT_ALREADY_POSTED, Map.of("countId", id));
        }
        if (status != expected) {
            throw new BusinessRuleViolation(InventoryErrorCode.COUNT_STATE_INVALID, Map.of("status", status.name()));
        }
    }

    public UUID id() {
        return id;
    }

    public UUID locationId() {
        return locationId;
    }

    public CountStatus status() {
        return status;
    }

    public @Nullable String note() {
        return note;
    }

    public List<UUID> variantIds() {
        return List.of(variantIds);
    }

    public List<StockCountLine> lines() {
        return List.copyOf(lines);
    }

    public Instant startedAt() {
        return startedAt;
    }

    public @Nullable Instant submittedAt() {
        return submittedAt;
    }

    public @Nullable Instant postedAt() {
        return postedAt;
    }

    public @Nullable UUID postedBy() {
        return postedBy;
    }

    public long version() {
        return version != null ? version : 0;
    }
}
