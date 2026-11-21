package com.bmp.web.idempotency;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Deletes expired idempotency records every 15 minutes, on one instance only (ShedLock). */
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyStore store;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(cron = "${bmp.web.idempotency-cleanup-cron:0 */15 * * * *}")
    @SchedulerLock(name = "idempotency-cleanup", lockAtMostFor = "PT5M")
    public void purgeExpired() {
        long started = clock.millis();
        log.debug("Idempotency cleanup started");
        int deleted = store.purgeExpired(clock.instant());
        log.atInfo()
                .addKeyValue("deleted", deleted)
                .addKeyValue("durationMs", clock.millis() - started)
                .log("Idempotency cleanup deleted {} expired records", deleted);
    }
}
