package com.bmp.finance.application;

import com.bmp.finance.api.events.ExpenseRecorded;
import com.bmp.finance.api.events.JournalPosted;
import com.bmp.finance.api.events.PeriodClosed;
import com.bmp.finance.api.events.ReceivableOverdue;
import com.bmp.finance.domain.model.Expense;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import java.math.BigDecimal;
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

    public void expenseRecorded(Expense e, UUID accountId) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new ExpenseRecorded(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                e.id(),
                0,
                stamp.metadata(),
                e.id(),
                e.categoryId(),
                accountId,
                e.amount(),
                e.currency(),
                e.paidFrom().name(),
                e.expenseDate(),
                e.drawerSessionId(),
                e.supplierId(),
                e.journalId()));
    }

    public void receivableOverdue(
            UUID customer, BigDecimal balance, BigDecimal overdue, int days, String currency, LocalDate asOf) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new ReceivableOverdue(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                customer,
                0,
                stamp.metadata(),
                customer,
                balance,
                overdue,
                days,
                currency,
                asOf));
    }

    public void periodClosed(UUID operationId, String period, boolean yearEnd, @Nullable UUID journal, Instant at) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new PeriodClosed(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                operationId,
                0,
                stamp.metadata(),
                period,
                yearEnd,
                journal,
                at));
    }

    public void closeRequested(UUID operationId, String period, List<UUID> acknowledged) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new PeriodCloseRequested(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                operationId,
                0,
                stamp.metadata(),
                operationId,
                period,
                acknowledged));
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
