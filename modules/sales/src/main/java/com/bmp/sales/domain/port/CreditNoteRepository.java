package com.bmp.sales.domain.port;

import com.bmp.sales.domain.model.CreditNote;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Credit notes of the current tenant; pages are newest first. */
public interface CreditNoteRepository {

    Optional<CreditNote> findById(UUID id);

    List<CreditNote> findPage(@Nullable UUID billId, @Nullable UUID beforeId, int limit);

    /** Every credit note of a bill, with its refund tenders. */
    List<CreditNote> findByBill(UUID billId);

    CreditNote saveAndFlush(CreditNote note);
}
