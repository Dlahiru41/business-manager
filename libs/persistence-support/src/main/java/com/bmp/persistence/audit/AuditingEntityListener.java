package com.bmp.persistence.audit;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.context.ActorContext;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;

/**
 * Default JPA entity listener (registered for every entity through {@code META-INF/bmp-orm.xml}) that stamps
 * {@link Auditable} entities with the injected {@link Clock} and the current actor.
 */
public class AuditingEntityListener {

    private final Clock clock;

    public AuditingEntityListener(Clock clock) {
        this.clock = clock;
    }

    @PrePersist
    void beforeInsert(Object entity) {
        if (entity instanceof Auditable auditable) {
            auditable.recordCreation(
                    clock.instant(), ActorContext.currentOrSystem().userId());
        }
    }

    @PreUpdate
    void beforeUpdate(Object entity) {
        if (entity instanceof Auditable auditable) {
            auditable.recordModification(
                    clock.instant(), ActorContext.currentOrSystem().userId());
        }
    }
}
