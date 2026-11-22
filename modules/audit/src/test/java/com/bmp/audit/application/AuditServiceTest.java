package com.bmp.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.audit.domain.model.AuditRecord.ChainHead;
import com.bmp.audit.domain.model.ChainVerification;
import com.bmp.audit.domain.port.AuditLedger;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    @Test
    void verifiesAChainLongerThanOneBatch() {
        int length = AuditService.VERIFY_BATCH + 1;
        List<AuditRecord> chain = new ArrayList<>();
        ChainHead head = ChainHead.EMPTY;
        for (int i = 0; i < length; i++) {
            AuditRecord record = AuditRecord.append(
                    UUID.randomUUID(),
                    TENANT,
                    head,
                    new AuditEntry(
                            Instant.EPOCH,
                            AuditSource.SYSTEM,
                            "job",
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    Instant.EPOCH);
            chain.add(record);
            head = new ChainHead(record.sequence(), record.hash());
        }
        AuditLedger ledger = mock(AuditLedger.class);
        when(ledger.chain(anyLong(), anyInt())).thenAnswer(invocation -> {
            long after = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return chain.stream().filter(r -> r.sequence() > after).limit(limit).toList();
        });

        ChainVerification.Result result = new AuditService(ledger, mock(IdGenerator.class), Clock.systemUTC()).verify();

        assertThat(result).isEqualTo(new ChainVerification.Result(true, length, null));
    }
}
