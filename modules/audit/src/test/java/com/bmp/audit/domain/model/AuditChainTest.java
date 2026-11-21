package com.bmp.audit.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.domain.model.AuditRecord.ChainHead;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditChainTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00.123456789Z");

    private static AuditEntry entry(String action, String userAgent) {
        return new AuditEntry(
                NOW,
                AuditSource.API,
                action,
                "users",
                UUID.randomUUID().toString(),
                201,
                UserId.of(UUID.randomUUID()),
                null,
                false,
                null,
                null,
                "10.0.0.1",
                userAgent,
                "req-1",
                null);
    }

    private static List<AuditRecord> chain(int length) {
        List<AuditRecord> records = new ArrayList<>();
        ChainHead head = ChainHead.EMPTY;
        for (int i = 0; i < length; i++) {
            AuditRecord record =
                    AuditRecord.append(UUID.randomUUID(), TENANT, head, entry("POST /api/v1/users", "ua"), NOW);
            records.add(record);
            head = new ChainHead(record.sequence(), record.hash());
        }
        return records;
    }

    private static ChainVerification.Result verify(List<AuditRecord> records) {
        ChainVerification verification = new ChainVerification();
        verification.accept(records.subList(0, records.size() / 2));
        verification.accept(records.subList(records.size() / 2, records.size()));
        return verification.result();
    }

    private static AuditRecord withAction(AuditRecord r, String action) {
        return new AuditRecord(
                r.id(),
                r.tenantId(),
                r.sequence(),
                r.occurredAt(),
                r.recordedAt(),
                r.source(),
                action,
                r.resourceType(),
                r.resourceId(),
                r.outcome(),
                r.actorId(),
                r.deviceId(),
                r.platformAdmin(),
                r.beforeHash(),
                r.afterHash(),
                r.ip(),
                r.userAgent(),
                r.requestId(),
                r.eventId(),
                r.prevHash(),
                r.hash());
    }

    @Test
    void linksEveryRecordToItsPredecessor() {
        List<AuditRecord> records = chain(5);

        assertThat(records.getFirst().sequence()).isEqualTo(1);
        assertThat(records.getFirst().prevHash()).isEqualTo(AuditRecord.GENESIS_HASH);
        assertThat(records.get(1).prevHash()).isEqualTo(records.getFirst().hash());
        assertThat(records.getFirst().occurredAt()).isEqualTo(Instant.parse("2026-11-22T10:00:00.123456Z"));
        assertThat(records.getFirst().hash())
                .hasSize(64)
                .isEqualTo(records.getFirst().computeHash());
        assertThat(verify(records)).isEqualTo(new ChainVerification.Result(true, 5, null));
    }

    @Test
    void detectsAChangedRecord() {
        List<AuditRecord> records = new ArrayList<>(chain(5));
        records.set(2, withAction(records.get(2), "DELETE /api/v1/users"));

        assertThat(verify(records)).isEqualTo(new ChainVerification.Result(false, 5, 3L));
    }

    @Test
    void detectsARemovedOrReorderedRecord() {
        List<AuditRecord> removed = new ArrayList<>(chain(5));
        removed.remove(1);
        List<AuditRecord> swapped = new ArrayList<>(chain(4));
        AuditRecord second = swapped.get(1);
        swapped.set(1, swapped.get(2));
        swapped.set(2, second);

        assertThat(verify(removed).firstBrokenSequence()).isEqualTo(2L);
        assertThat(verify(swapped).firstBrokenSequence()).isEqualTo(2L);
        assertThat(verify(List.of()).intact()).isTrue();
    }

    @Test
    void distinguishesAbsentFromEmptyAndCutsLongUserAgents() {
        AuditRecord empty = AuditRecord.append(UUID.randomUUID(), TENANT, ChainHead.EMPTY, entry("A", ""), NOW);
        AuditRecord absent = new AuditRecord(
                empty.id(),
                empty.tenantId(),
                empty.sequence(),
                empty.occurredAt(),
                empty.recordedAt(),
                empty.source(),
                empty.action(),
                empty.resourceType(),
                empty.resourceId(),
                empty.outcome(),
                empty.actorId(),
                empty.deviceId(),
                empty.platformAdmin(),
                empty.beforeHash(),
                empty.afterHash(),
                empty.ip(),
                null,
                empty.requestId(),
                empty.eventId(),
                empty.prevHash(),
                empty.hash());
        AuditRecord longAgent =
                AuditRecord.append(UUID.randomUUID(), TENANT, ChainHead.EMPTY, entry("A", "x".repeat(1000)), NOW);

        assertThat(absent.computeHash()).isNotEqualTo(empty.hash());
        assertThat(longAgent.userAgent()).hasSize(AuditRecord.MAX_USER_AGENT);
        assertThat(AuditRecord.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
