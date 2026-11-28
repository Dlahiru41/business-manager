package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A payment to a supplier and the documents it was allocated to (FIN-11); never changed. */
public record SupplierPayment(
        UUID id,
        UUID supplierId,
        BigDecimal amount,
        String currency,
        boolean fromDrawer,
        @Nullable UUID drawerSessionId,
        @Nullable String reference,
        Instant paidAt,
        List<Allocated> allocations,
        UUID journalId) {

    public SupplierPayment {
        allocations = List.copyOf(allocations);
    }

    /** What the payment was meant for. */
    public record Allocated(UUID documentId, BigDecimal amount) {}
}
