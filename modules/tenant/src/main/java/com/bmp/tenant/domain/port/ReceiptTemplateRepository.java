package com.bmp.tenant.domain.port;

import com.bmp.tenant.domain.model.ReceiptTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Receipt templates of the current tenant. */
public interface ReceiptTemplateRepository {

    Optional<ReceiptTemplate> findById(UUID id);

    Optional<ReceiptTemplate> findDefault();

    ReceiptTemplate save(ReceiptTemplate template);

    /** Saves and flushes at once, so a default-flag change hits the database before the next insert. */
    ReceiptTemplate saveAndFlush(ReceiptTemplate template);

    List<ReceiptTemplate> findPage(Instant afterCreatedAt, UUID afterId, int limit);
}
