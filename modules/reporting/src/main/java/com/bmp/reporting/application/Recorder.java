package com.bmp.reporting.application;

import com.bmp.reporting.domain.model.Fact;
import com.bmp.reporting.domain.port.EventLog;
import com.bmp.reporting.domain.port.ReadModels;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule REP-01: an event's fact is logged and applied in one transaction; a redelivered event is already in the log
 * and changes nothing. Writers hold the tenant's shared lock, so a rebuild never runs under them.
 */
@Component
public class Recorder {

    private final EventLog log;
    private final ReadModels models;
    private final Projector projector;

    public Recorder(EventLog log, ReadModels models, Projector projector) {
        this.log = log;
        this.models = models;
        this.projector = projector;
    }

    @Transactional
    public void record(UUID eventId, Instant occurredAt, Fact fact) {
        models.lock(false);
        if (log.append(eventId, occurredAt, fact)) {
            projector.apply(eventId, fact);
        }
    }
}
