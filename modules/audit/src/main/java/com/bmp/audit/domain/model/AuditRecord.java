package com.bmp.audit.domain.model;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One link of a tenant's audit chain (a ledger row: never updated or deleted). Its {@code hash} is the SHA-256 over
 * every other field and the previous record's hash, so changing, removing or reordering any record breaks the chain
 * from that point on. The IP address and user agent are personal data kept for investigations only: they are left out
 * of the hash, so that erasing them (ADR-017) keeps the chain verifiable.
 *
 * @param sequence position in the tenant's chain, starting at 1, without gaps
 * @param prevHash hash of the previous record, {@link #GENESIS_HASH} for the first
 */
public record AuditRecord(
        UUID id,
        TenantId tenantId,
        long sequence,
        Instant occurredAt,
        Instant recordedAt,
        AuditSource source,
        String action,
        @Nullable String resourceType,
        @Nullable String resourceId,
        @Nullable Integer outcome,
        @Nullable UserId actorId,
        @Nullable DeviceId deviceId,
        boolean platformAdmin,
        @Nullable String beforeHash,
        @Nullable String afterHash,
        @Nullable String ip,
        @Nullable String userAgent,
        @Nullable String requestId,
        @Nullable UUID eventId,
        String prevHash,
        String hash) {

    /** The previous hash of a chain's first record. */
    public static final String GENESIS_HASH = "0".repeat(64);

    /** Longest user agent kept; longer values are cut. */
    public static final int MAX_USER_AGENT = 256;

    public AuditRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }

    /**
     * The next record of a chain. Instants are kept to the microsecond, the database precision, so the hash can be
     * recomputed from the stored row.
     */
    public static AuditRecord append(UUID id, TenantId tenant, ChainHead head, AuditEntry entry, Instant recordedAt) {
        String userAgent = entry.userAgent();
        AuditRecord unsigned = new AuditRecord(
                id,
                tenant,
                head.sequence() + 1,
                micros(entry.occurredAt()),
                micros(recordedAt),
                entry.source(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome(),
                entry.actorId(),
                entry.deviceId(),
                entry.platformAdmin(),
                entry.beforeHash(),
                entry.afterHash(),
                entry.ip(),
                userAgent != null && userAgent.length() > MAX_USER_AGENT
                        ? userAgent.substring(0, MAX_USER_AGENT)
                        : userAgent,
                entry.requestId(),
                entry.eventId(),
                head.hash(),
                "");
        return unsigned.withHash(unsigned.computeHash());
    }

    /** Recomputes the hash from the fields; differs from {@link #hash()} when the record was tampered with. */
    public String computeHash() {
        StringBuilder canonical = new StringBuilder();
        field(canonical, id);
        field(canonical, tenantId);
        field(canonical, sequence);
        field(canonical, micros(occurredAt));
        field(canonical, micros(recordedAt));
        field(canonical, source);
        field(canonical, action);
        field(canonical, resourceType);
        field(canonical, resourceId);
        field(canonical, outcome);
        field(canonical, actorId);
        field(canonical, deviceId);
        field(canonical, platformAdmin);
        field(canonical, beforeHash);
        field(canonical, afterHash);
        field(canonical, requestId);
        field(canonical, eventId);
        field(canonical, prevHash);
        return sha256(canonical.toString());
    }

    /** SHA-256 of UTF-8 text as lower-case hex. */
    public static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private AuditRecord withHash(String newHash) {
        return new AuditRecord(
                id,
                tenantId,
                sequence,
                occurredAt,
                recordedAt,
                source,
                action,
                resourceType,
                resourceId,
                outcome,
                actorId,
                deviceId,
                platformAdmin,
                beforeHash,
                afterHash,
                ip,
                userAgent,
                requestId,
                eventId,
                prevHash,
                newHash);
    }

    /** Length-prefixed, so that no two different records share a canonical form; null differs from "". */
    private static void field(StringBuilder canonical, @Nullable Object value) {
        if (value == null) {
            canonical.append("-;");
            return;
        }
        String text = value.toString();
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The end of a chain: the last sequence and hash ({@link #EMPTY} before the first record).
     *
     * @param sequence 0 for an empty chain
     */
    public record ChainHead(long sequence, String hash) {

        public static final ChainHead EMPTY = new ChainHead(0, GENESIS_HASH);
    }
}
