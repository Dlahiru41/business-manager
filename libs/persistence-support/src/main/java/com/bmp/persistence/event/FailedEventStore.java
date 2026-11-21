package com.bmp.persistence.event;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.DomainException;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.TenantId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parks events a listener cannot accept ({@link DomainException}: retrying will not help) in
 * {@code platform.failed_event}, with the problem, for inspection and replay (04-error-handling.md §7). Runs in its
 * own transaction, in the event's tenant, because the listener's transaction has already rolled back.
 */
public class FailedEventStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final IdGenerator ids;
    private final Clock clock;

    public FailedEventStore(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper json,
            IdGenerator ids,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.ids = ids;
        this.clock = clock;
    }

    public void park(String consumer, DomainEvent event, DomainException failure) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("code", failure.errorCode().code());
        problem.put("category", failure.errorCode().category().name());
        problem.put("details", failure.details());
        var now = Jsonb.utc(clock.instant());
        TenantContext.runWith(
                event.tenantId(),
                () -> transactions.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO platform.failed_event (id, tenant_id, consumer, event_id, event_type, payload, problem,
                                                           attempts, first_failed_at, last_failed_at, status)
                        VALUES (:id, :tenantId, :consumer, :eventId, :eventType, :payload, :problem, 1, :now, :now,
                                'PARKED')
                        ON CONFLICT (consumer, event_id) DO UPDATE
                           SET attempts = platform.failed_event.attempts + 1,
                               problem = EXCLUDED.problem,
                               last_failed_at = EXCLUDED.last_failed_at,
                               status = 'PARKED'
                        """)
                        .param("id", ids.newUuid())
                        .param("tenantId", event.tenantId().value())
                        .param("consumer", consumer)
                        .param("eventId", event.eventId())
                        .param("eventType", event.getClass().getName())
                        .param("payload", Jsonb.of(json.writeValueAsString(event)))
                        .param("problem", Jsonb.of(json.writeValueAsString(problem)))
                        .param("now", now)
                        .update()));
    }

    /** Whether {@code consumer} parked {@code eventId}; parked publications are abandoned instead of retried. */
    public boolean isParked(String consumer, UUID eventId, TenantId tenant) {
        return TenantContext.callWith(
                        tenant,
                        () -> transactions.execute(status -> jdbc.sql("SELECT count(*) FROM platform.failed_event"
                                        + " WHERE consumer = :consumer AND event_id = :eventId AND status = 'PARKED'")
                                .param("consumer", consumer)
                                .param("eventId", eventId)
                                .query(Long.class)
                                .single()))
                > 0;
    }
}
