package com.bmp.persistence.event;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes completed event publications older than the retention (7 days by default) once a night, on one instance.
 * The outbox is for delivery, not history: the audit trail and the publishing modules keep what must be kept.
 */
public class CompletedEventPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CompletedEventPurgeJob.class);

    private final CompletedEventPublications completed;
    private final Duration retention;

    public CompletedEventPurgeJob(CompletedEventPublications completed, Duration retention) {
        this.completed = completed;
        this.retention = retention;
    }

    @Scheduled(cron = "${bmp.events.purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "event-purge", lockAtMostFor = "PT30M")
    public void purge() {
        completed.deletePublicationsOlderThan(retention);
        log.info("Purged completed event publications olderThan={}", retention);
    }
}
