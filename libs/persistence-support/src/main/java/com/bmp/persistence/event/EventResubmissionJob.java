package com.bmp.persistence.event;

import com.bmp.shared.event.DomainEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.AbandonPolicy;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retries failed event publications (04-error-handling.md §7): only those older than a minute, with exponential
 * back-off ({@code 1 min × 2^(attempts-1)}), at most {@value #MAX_ATTEMPTS} attempts. Publications that exhausted
 * their attempts, or whose event the listener parked as non-retryable, are abandoned; exhausted ones raise
 * {@code bmp.events.failed} and an ERROR log.
 */
public class EventResubmissionJob {

    public static final int MAX_ATTEMPTS = 10;
    private static final Duration BASE_DELAY = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(EventResubmissionJob.class);

    private final FailedEventPublications failed;
    private final FailedEventStore parked;
    private final MeterRegistry meters;
    private final Clock clock;

    public EventResubmissionJob(
            FailedEventPublications failed, FailedEventStore parked, MeterRegistry meters, Clock clock) {
        this.failed = failed;
        this.parked = parked;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bmp.events.resubmission-interval:PT1M}")
    @SchedulerLock(name = "event-resubmission", lockAtMostFor = "PT5M")
    public void resubmit() {
        log.debug("Event resubmission started");
        failed.applyAbandonPolicy(this::abandonDecision);
        failed.resubmit(ResubmissionOptions.defaults().withMinAge(BASE_DELAY).withFilter(this::isDue));
    }

    AbandonPolicy.Decision abandonDecision(EventPublication publication) {
        if (isParked(publication)) {
            return AbandonPolicy.Decision.ABANDON;
        }
        if (publication.getCompletionAttempts() >= MAX_ATTEMPTS) {
            meters.counter("bmp.events.failed", "listener", listenerOf(publication))
                    .increment();
            log.atError()
                    .addKeyValue("publicationId", publication.getIdentifier())
                    .addKeyValue("attempts", publication.getCompletionAttempts())
                    .log(
                            "Event publication abandoned after {} attempts publicationId={}",
                            publication.getCompletionAttempts(),
                            publication.getIdentifier());
            return AbandonPolicy.Decision.ABANDON;
        }
        return AbandonPolicy.Decision.RETAIN;
    }

    boolean isDue(EventPublication publication) {
        int attempts = Math.max(1, publication.getCompletionAttempts());
        Duration backoff = BASE_DELAY.multipliedBy(1L << Math.min(attempts - 1, 20));
        Instant last = publication.getLastPublicationDate();
        return !last.plus(backoff).isAfter(clock.instant());
    }

    private boolean isParked(EventPublication publication) {
        return publication instanceof TargetEventPublication target
                && publication.getEvent() instanceof DomainEvent event
                && parked.isParked(target.getTargetIdentifier().getValue(), event.eventId(), event.tenantId());
    }

    private static String listenerOf(EventPublication publication) {
        if (publication instanceof TargetEventPublication target) {
            String id = target.getTargetIdentifier().getValue();
            int paren = id.indexOf('(');
            String method = paren > 0 ? id.substring(0, paren) : id;
            int lastDot = method.lastIndexOf('.');
            String type = lastDot > 0 ? method.substring(0, lastDot) : method;
            return type.substring(type.lastIndexOf('.') + 1);
        }
        return "unknown";
    }
}
