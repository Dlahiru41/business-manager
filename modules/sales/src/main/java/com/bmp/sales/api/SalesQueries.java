package com.bmp.sales.api;

import com.bmp.sales.api.dto.BillReceipt;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.shared.id.RegisterId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask sales about the current tenant. */
public interface SalesQueries {

    Optional<RegisterInfo> findRegister(RegisterId register);

    /** Rule SYN-06: the sequence numbers of the register's bills carrying its current prefix, ascending. */
    List<Long> billSequences(RegisterId register);

    /** A completed (or returned) bill as its receipt shows it; empty for drafts, voided and unknown bills. */
    Optional<BillReceipt> findReceipt(UUID billId);
}
