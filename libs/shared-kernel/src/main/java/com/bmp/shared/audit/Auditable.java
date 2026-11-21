package com.bmp.shared.audit;

import com.bmp.shared.id.UserId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An entity whose {@code created_at/by} and {@code updated_at/by} columns are stamped by the persistence layer from
 * the injected {@code Clock} and the current actor, so domain code never sets them by hand.
 */
public interface Auditable {

    /** Called once, before the entity is first inserted. */
    void recordCreation(Instant at, @Nullable UserId by);

    /** Called before every update of the entity. */
    void recordModification(Instant at, @Nullable UserId by);
}
