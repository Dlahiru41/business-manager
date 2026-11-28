package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.CashDrop;
import com.bmp.finance.domain.model.Expense;
import com.bmp.finance.domain.model.PaidFrom;
import com.bmp.finance.domain.model.SupplierPayment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Expenses, supplier payments and cash drops: insert and read only. */
public interface DocumentLedger {

    void append(Expense expense);

    Optional<Expense> expense(UUID id);

    /** Newest first by date, then id, after the cursor. */
    List<Expense> expenses(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable UUID categoryId,
            @Nullable PaidFrom paidFrom,
            @Nullable Cursor after,
            int limit);

    void append(SupplierPayment payment);

    Optional<SupplierPayment> supplierPayment(UUID id);

    List<SupplierPayment> supplierPayments(@Nullable UUID supplierId, @Nullable UUID beforeId, int limit);

    void append(CashDrop drop);

    Optional<CashDrop> cashDrop(UUID id);

    List<CashDrop> cashDrops(@Nullable UUID drawerSessionId, @Nullable UUID beforeId, int limit);

    /** A position in a list sorted by date descending, then id descending. */
    record Cursor(LocalDate date, UUID id) {}
}
