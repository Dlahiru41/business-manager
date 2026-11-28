package com.bmp.reporting.application;

import com.bmp.persistence.operation.Operations;
import com.bmp.reporting.domain.port.EventLog;
import com.bmp.reporting.domain.port.ReadModels;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.IdGenerator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule REP-01: the tenant's read models rebuilt from the event log, in recorded order, by the same projector the
 * listeners use; under the tenant's exclusive lock, in one transaction.
 */
@Component
public class Rebuild {

    static final String OPERATION = "REPORTING_REBUILD";
    static final int BATCH = 1000;

    private static final Logger log = LoggerFactory.getLogger(Rebuild.class);

    private final EventLog events;
    private final ReadModels models;
    private final Projector projector;
    private final Operations operations;
    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;
    private final IdGenerator ids;

    public Rebuild(
            EventLog events,
            ReadModels models,
            Projector projector,
            Operations operations,
            ApplicationEventPublisher publisher,
            EventStampSource stamps,
            IdGenerator ids) {
        this.events = events;
        this.models = models;
        this.projector = projector;
        this.operations = operations;
        this.publisher = publisher;
        this.stamps = stamps;
        this.ids = ids;
    }

    /** Accepts a rebuild; it runs in the background. */
    @Transactional
    public Operations.Operation request() {
        UUID operation = ids.newUuid();
        operations.create(operation, OPERATION);
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new RebuildRequested(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                operation,
                0,
                stamp.metadata(),
                operation));
        return operations.find(operation).orElseThrow();
    }

    /** Runs a requested rebuild once. */
    @Transactional
    public void run(UUID operation) {
        if (operations.find(operation).map(Operations.Operation::isFinished).orElse(true)) {
            return;
        }
        int replayed = replay();
        operations.succeeded(operation, null);
        log.atInfo().addKeyValue("facts", replayed).log("Reporting read models rebuilt");
    }

    /** Clears the read models and applies the whole log again; returns how many facts were applied. */
    @Transactional
    public int replay() {
        models.lock(true);
        models.clear();
        int offset = 0;
        while (true) {
            List<EventLog.Entry> batch = events.read(offset, BATCH);
            batch.forEach(e -> projector.apply(null, e.fact()));
            offset += batch.size();
            if (batch.size() < BATCH) {
                return offset;
            }
        }
    }
}
