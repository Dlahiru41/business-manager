package com.bmp.persistence.event;

import com.bmp.persistence.jdbc.Jsonb;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once effects for event listeners (07-events-and-messaging.md §4): records {@code (consumer, eventId)} in
 * {@code platform.inbox} inside the listener's own transaction. Because the inbox row and the business change commit
 * together, a redelivered event finds its row and is skipped.
 */
public class InboxGuard {

    private final JdbcClient jdbc;
    private final Clock clock;

    public InboxGuard(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Claims the event for the consumer.
     *
     * @param consumer a stable listener name, e.g. {@code iam.TenantCreatedListener}
     * @return {@code true} on first delivery; {@code false} if the event was already processed (skip it)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, UUID eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO platform.inbox (consumer, event_id, processed_at)
                        VALUES (:consumer, :eventId, :processedAt)
                        ON CONFLICT (consumer, event_id) DO NOTHING
                        """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("processedAt", Jsonb.utc(clock.instant()))
                .update();
        return inserted == 1;
    }
}
