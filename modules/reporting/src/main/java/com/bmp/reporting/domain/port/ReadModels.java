package com.bmp.reporting.domain.port;

import com.bmp.reporting.domain.model.Fact;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The read models of the current tenant: facts and daily summaries, written as facts are applied. */
public interface ReadModels {

    /** Serialises the tenant's writers against a rebuild (shared) or a rebuild against them (exclusive). */
    void lock(boolean exclusive);

    void bill(Fact.Sale sale);

    boolean billKnown(UUID billId);

    /** The location of a bill already recorded. */
    Optional<UUID> billLocation(UUID billId);

    void voidBill(UUID billId, LocalDate businessDate);

    /** A sold bill line as {@code fact_sale_line} keeps it. */
    record SoldLine(
            UUID billLineId,
            UUID variantId,
            UUID packagingUnitId,
            BigDecimal qty,
            BigDecimal qtyBase,
            BigDecimal revenue,
            BigDecimal discount,
            BigDecimal tax) {}

    List<SoldLine> soldLines(UUID billId);

    /** The sale half of an entry. */
    record SaleHalf(
            UUID entryId,
            String kind,
            UUID billId,
            UUID billLineId,
            UUID sourceId,
            LocalDate businessDate,
            UUID locationId,
            @Nullable String channel,
            @Nullable UUID customerId,
            @Nullable UUID cashierId,
            UUID variantId,
            @Nullable UUID packagingUnitId,
            BigDecimal qty,
            BigDecimal qtyBase,
            BigDecimal revenue,
            BigDecimal discount,
            BigDecimal tax) {}

    /** Writes the sale half; returns the cost already there, if its cost half came first. */
    Optional<BigDecimal> saleHalf(SaleHalf half);

    /** Where an entry's sale half put it, once that half arrived. */
    record Placed(LocalDate businessDate, UUID locationId) {}

    /** Writes the cost half; returns where the entry's sale half put it, if that came first. */
    Optional<Placed> costHalf(UUID entryId, String kind, UUID billId, UUID billLineId, UUID sourceId, BigDecimal cost);

    /** Adds to a day's summary at a location. */
    void summarize(
            LocalDate businessDate,
            UUID locationId,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal expenses,
            int bills);

    void payment(Fact.Payment payment);

    void expense(Fact.Expense expense);

    void drawer(Fact.Drawer drawer);

    void movement(Fact.Movement movement);

    void variant(Fact.VariantNamed variant);

    void product(Fact.ProductNamed product);

    /** REP-07: what a return should give back in money, and what payments gave back for it. */
    void returnWatch(Fact.Return returned);

    void refundSeen(UUID creditNoteId, BigDecimal amount);

    /** Removes every read model of the tenant before a rebuild (the event log stays). */
    void clear();
}
