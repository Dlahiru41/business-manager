package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** {@code DrawerReconciled}: short Dr CASH_OVER_SHORT · Cr CASH_DRAWER; over the other way; nothing at zero. */
public final class DrawerReconciledRule implements PostingRule<DrawerReconciledRule.Reconciliation> {

    /** A reconciled drawer; {@code variance} = counted − expected. */
    public record Reconciliation(UUID eventId, UUID sessionId, BigDecimal variance, LocalDate businessDate) {}

    @Override
    public Optional<JournalEntry> post(Reconciliation r) {
        return JournalEntry.builder(SourceType.DRAWER_RECONCILIATION, r.sessionId(), r.eventId(), r.businessDate())
                .memo("Drawer variance")
                .debit(SystemKey.CASH_DRAWER, r.variance(), Dimensions.NONE)
                .credit(SystemKey.CASH_OVER_SHORT, r.variance(), Dimensions.NONE)
                .buildIfAny();
    }
}
