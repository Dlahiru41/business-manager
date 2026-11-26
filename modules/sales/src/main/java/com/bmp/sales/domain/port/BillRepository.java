package com.bmp.sales.domain.port;

import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Bills of the current tenant; pages are newest first. */
public interface BillRepository {

    Optional<Bill> findById(UUID id);

    /** The bill, locked until the transaction ends (void, return). */
    Optional<Bill> lock(UUID id);

    Optional<Bill> findByNumber(String number);

    /** Filters applied when given. */
    record Filter(
            @Nullable BillStatus status,
            @Nullable UUID registerId,
            @Nullable UUID customerId,
            @Nullable LocalDate businessDate,
            boolean flaggedOnly) {}

    List<Bill> findPage(Filter filter, @Nullable UUID beforeId, int limit);

    /** Σ CREDIT tenders of the customer's completed bills less what credit notes credited back (SAL-07 default). */
    BigDecimal creditExposure(UUID customerId);

    /** Whether the tenant has any completed bill (TEN-02). */
    boolean anyCompleted();

    Bill saveAndFlush(Bill bill);
}
