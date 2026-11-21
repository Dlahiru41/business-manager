package com.bmp.audit.application;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditWriter;
import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.audit.domain.model.ChainVerification;
import com.bmp.audit.domain.port.AuditLedger;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to and verifies the current tenant's audit chain. Appends of one tenant are serialized by a transaction
 * lock, so sequence numbers have no gaps and every record links to its predecessor.
 */
@Service
public class AuditService implements AuditWriter {

    /** Records read per round trip while verifying. */
    static final int VERIFY_BATCH = 500;

    private final AuditLedger ledger;
    private final IdGenerator ids;
    private final Clock clock;

    public AuditService(AuditLedger ledger, IdGenerator ids, Clock clock) {
        this.ledger = ledger;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        ledger.lockChain();
        ledger.append(
                AuditRecord.append(ids.newUuid(), TenantContext.require(), ledger.head(), entry, clock.instant()));
    }

    /** Recomputes the whole chain of the current tenant; the result names the first broken record, if any. */
    @Transactional(readOnly = true)
    public ChainVerification.Result verify() {
        ChainVerification verification = new ChainVerification();
        long after = 0;
        List<AuditRecord> batch;
        do {
            batch = ledger.chain(after, VERIFY_BATCH);
            verification.accept(batch);
            if (!batch.isEmpty()) {
                after = batch.getLast().sequence();
            }
        } while (batch.size() == VERIFY_BATCH);
        return verification.result();
    }
}
