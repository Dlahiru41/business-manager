package com.bmp.sales.domain.port;

import com.bmp.sales.domain.model.Shift;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Shifts of the current tenant. */
public interface ShiftRepository {

    Optional<Shift> findById(UUID id);

    /** The register's OPEN shift (at most one, SAL-12). */
    Optional<Shift> findOpen(UUID registerId);

    /** The shift of the register that was running at {@code at}, the latest first. */
    Optional<Shift> findCovering(UUID registerId, Instant at);

    /** The register's synthetic shift for offline bills without one (SAL-12). */
    Optional<Shift> findOffline(UUID registerId);

    Shift saveAndFlush(Shift shift);
}
