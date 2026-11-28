package com.bmp.finance.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A balanced journal was posted (FIN-02, FIN-04) — {@code bmp.finance.journal-posted.v1}. */
public record JournalPosted(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID journalId,
        String number,
        LocalDate entryDate,
        String period,
        String sourceType,
        UUID sourceId,
        @Nullable UUID sourceEventId,
        @Nullable UUID reversesId,
        @Nullable String memo,
        String currency,
        List<Line> lines,
        Instant postedAt)
        implements DomainEvent {

    public JournalPosted {
        lines = List.copyOf(lines);
    }

    /** A line; one of debit and credit is zero. */
    public record Line(
            UUID accountId,
            String accountCode,
            @Nullable String systemKey,
            BigDecimal debit,
            BigDecimal credit,
            @Nullable String partyType,
            @Nullable UUID partyId) {}
}
