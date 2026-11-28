package com.bmp.finance.domain.port;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** The line totals of posted goods receipts, by line id (ADR-032 §5). */
public interface ReceiptLines {

    void save(UUID receiptId, Map<UUID, BigDecimal> totals);

    Map<UUID, BigDecimal> totals(Collection<UUID> lineIds);
}
