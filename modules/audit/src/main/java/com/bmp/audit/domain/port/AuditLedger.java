package com.bmp.audit.domain.port;

import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.audit.domain.model.AuditRecord.ChainHead;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The audit ledger of the current tenant: append and read only. */
public interface AuditLedger {

    /** Serializes appends to the tenant's chain until the current transaction ends. */
    void lockChain();

    /** The last record's sequence and hash, or {@link ChainHead#EMPTY}. */
    ChainHead head();

    void append(AuditRecord record);

    /**
     * Records matching the filter, newest first, with a sequence below {@code beforeSequence}.
     *
     * @param from inclusive lower bound of {@code occurredAt}
     * @param to exclusive upper bound of {@code occurredAt}
     */
    List<AuditRecord> find(
            @Nullable String resourceType,
            @Nullable String resourceId,
            @Nullable Instant from,
            @Nullable Instant to,
            long beforeSequence,
            int limit);

    /** The chain in sequence order, starting after {@code afterSequence}. */
    List<AuditRecord> chain(long afterSequence, int limit);
}
