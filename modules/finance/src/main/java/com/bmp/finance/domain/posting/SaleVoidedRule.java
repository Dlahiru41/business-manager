package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code SaleVoided}: the exact reversal of the bill's SALE journal; money and stock come back through their own
 * events (ADR-032 §4).
 */
public final class SaleVoidedRule implements PostingRule<SaleVoidedRule.Voided> {

    /** A voided bill and the journal its sale posted. */
    public record Voided(UUID eventId, UUID billId, String number, LocalDate businessDate, PostedJournal sale) {}

    @Override
    public Optional<JournalEntry> post(Voided v) {
        return Optional.of(v.sale()
                .reversal(
                        SourceType.SALE_VOID, v.billId(), v.eventId(), v.businessDate(), "Void of bill " + v.number()));
    }
}
