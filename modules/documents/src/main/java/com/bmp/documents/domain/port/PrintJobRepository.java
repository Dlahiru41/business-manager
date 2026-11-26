package com.bmp.documents.domain.port;

import com.bmp.documents.domain.model.PrintJob;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Print jobs of the current tenant. */
public interface PrintJobRepository {

    Optional<PrintJob> findById(UUID id);

    Optional<PrintJob> lock(UUID id);

    /** The oldest job the agent may take now, locked; jobs another poll holds are skipped. */
    Optional<PrintJob> nextDue(UUID agentId, Instant now);

    PrintJob saveAndFlush(PrintJob job);
}
