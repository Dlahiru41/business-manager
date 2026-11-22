package com.bmp.persistence.event;

import com.bmp.persistence.jdbc.Jsonb;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Readiness signal for the outbox (05-logging-and-observability.md §8): DOWN when more than {@code threshold} event
 * publications have stayed incomplete for longer than {@code maxAge}. Abandoned publications are excluded (they have
 * already raised an alert).
 */
public class EventBacklogHealthIndicator implements HealthIndicator {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final long threshold;
    private final Duration maxAge;

    public EventBacklogHealthIndicator(JdbcClient jdbc, Clock clock, long threshold, Duration maxAge) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.threshold = threshold;
        this.maxAge = maxAge;
    }

    @Override
    public Health health() {
        long stale = jdbc.sql("""
                        SELECT count(*) FROM platform.event_publication
                         WHERE completion_date IS NULL
                           AND (status IS NULL OR status <> 'ABANDONED')
                           AND publication_date < :cutoff
                        """)
                .param("cutoff", Jsonb.utc(clock.instant().minus(maxAge)))
                .query(Long.class)
                .single();
        Health.Builder health = stale > threshold ? Health.down() : Health.up();
        return health.withDetail("staleIncompletePublications", stale)
                .withDetail("threshold", threshold)
                .withDetail("maxAge", maxAge.toString())
                .build();
    }
}
