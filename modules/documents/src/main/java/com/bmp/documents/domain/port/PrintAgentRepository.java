package com.bmp.documents.domain.port;

import com.bmp.documents.domain.model.PrintAgent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Print agents of the current tenant. */
public interface PrintAgentRepository {

    Optional<PrintAgent> findById(UUID id);

    /** The agents of a location, oldest first. */
    List<PrintAgent> atLocation(UUID locationId);

    /** Newest first, from before {@code beforeId}. */
    List<PrintAgent> page(@Nullable UUID locationId, @Nullable UUID beforeId, int limit);

    PrintAgent saveAndFlush(PrintAgent agent);
}
