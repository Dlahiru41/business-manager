package com.bmp.audit.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.domain.model.AuditRecord.ChainHead;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Every field but the personal ones (IP, user agent) is in the hash, so changing any of them breaks the chain. */
class AuditRecordHashTest {

    static final Instant AT = Instant.parse("2026-11-23T09:00:00.123456Z");

    static AuditRecord record(String userAgent) {
        return AuditRecord.append(
                UUID.fromString("0193a000-0000-7000-8000-00000000aa01"),
                TenantId.of(UUID.fromString("0193a000-0000-7000-8000-000000000001")),
                new ChainHead(4, "a".repeat(64)),
                new AuditEntry(
                        AT,
                        AuditSource.API,
                        "POST /api/v1/goods-receipts",
                        "GoodsReceipt",
                        "0193f000-0000-7000-8000-000000000201",
                        201,
                        UserId.of(UUID.fromString("0193a6f1-0000-7000-8000-00000000f001")),
                        DeviceId.of(UUID.fromString("0193a6f1-0000-7000-8000-00000000d001")),
                        false,
                        "b".repeat(64),
                        "c".repeat(64),
                        "192.0.2.10",
                        userAgent,
                        "req-1",
                        UUID.fromString("0193f1a0-0000-7000-8000-00000000e001")),
                AT.plusSeconds(1));
    }

    static AuditRecord copy(AuditRecord r, UnaryOperator<Object[]> change) {
        Object[] f = {
            r.id(),
            r.tenantId(),
            r.sequence(),
            r.occurredAt(),
            r.recordedAt(),
            r.source(),
            r.action(),
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
            r.hash()
        };
        Object[] c = change.apply(f.clone());
        return new AuditRecord(
                (UUID) c[0],
                (TenantId) c[1],
                (Long) c[2],
                (Instant) c[3],
                (Instant) c[4],
                (AuditSource) c[5],
                (String) c[6],
                (String) c[7],
                (String) c[8],
                (Integer) c[9],
                (UserId) c[10],
                (DeviceId) c[11],
                (Boolean) c[12],
                (String) c[13],
                (String) c[14],
                (String) c[15],
                (String) c[16],
                (String) c[17],
                (UUID) c[18],
                (String) c[19],
                (String) c[20]);
    }

    static UnaryOperator<Object[]> set(int index, Object value) {
        return f -> {
            f[index] = value;
            return f;
        };
    }

    @Test
    void changingAnyChainedFieldChangesTheHash() {
        AuditRecord original = record("curl/8");
        List<UnaryOperator<Object[]>> changes = List.of(
                set(0, UUID.randomUUID()),
                set(1, TenantId.of(UUID.randomUUID())),
                set(2, 6L),
                set(3, AT.plusMillis(1)),
                set(4, AT.plusSeconds(2)),
                set(5, AuditSource.EVENT),
                set(6, "POST /api/v1/purchase-orders"),
                set(7, "PurchaseOrder"),
                set(8, "x"),
                set(9, 200),
                set(10, UserId.of(UUID.randomUUID())),
                set(11, DeviceId.of(UUID.randomUUID())),
                set(12, true),
                set(13, "d".repeat(64)),
                set(14, "e".repeat(64)),
                set(17, "req-2"),
                set(18, UUID.randomUUID()),
                set(19, "f".repeat(64)));

        assertThat(original.computeHash()).isEqualTo(original.hash());
        for (int i = 0; i < changes.size(); i++) {
            assertThat(copy(original, changes.get(i)).computeHash())
                    .as("change %d", i)
                    .isNotEqualTo(original.hash());
        }
        assertThat(copy(original, set(15, "198.51.100.7")).computeHash())
                .as("the IP address is personal data outside the hash")
                .isEqualTo(original.hash());
        assertThat(copy(original, set(16, null)).computeHash())
                .as("so is the user agent, erasable without breaking the chain")
                .isEqualTo(original.hash());
    }

    @Test
    void userAgentsAreCutAtTheMaximumLength() {
        String longest = "u".repeat(AuditRecord.MAX_USER_AGENT);

        assertThat(record(longest).userAgent()).isEqualTo(longest);
        assertThat(record(longest + "v").userAgent()).isEqualTo(longest);
        assertThat(record(null).userAgent()).isNull();
        assertThat(record("x").sequence()).isEqualTo(5);
        assertThat(record("x").prevHash()).isEqualTo("a".repeat(64));
    }
}
