package com.bmp.finance.application;

import com.bmp.finance.api.events.JournalPosted;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes the finance events inside the caller's transaction (07 §3, outbox). */
@Component
public class FinanceEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public FinanceEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void journalPosted(JournalPostedFacts j) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new JournalPosted(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                j.journalId(),
                0,
                stamp.metadata(),
                j.journalId(),
                j.number(),
                j.entryDate(),
                j.period(),
                j.sourceType(),
                j.sourceId(),
                j.sourceEventId(),
                j.reversesId(),
                j.memo(),
                j.currency(),
                j.lines(),
                j.postedAt()));
    }

    /** What a {@code JournalPosted} says. */
    public record JournalPostedFacts(
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
            List<JournalPosted.Line> lines,
            Instant postedAt) {}
}
