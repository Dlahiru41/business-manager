package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.service.PlannedMovement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The insert-only stock ledger (INV-01); rows are never changed or deleted. */
public interface MovementLedger {

    void append(
            List<PlannedMovement> movements,
            String currency,
            Instant recordedAt,
            @Nullable UUID actorId,
            @Nullable UUID deviceId);
}
