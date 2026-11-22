package com.bmp.web.idempotency;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.persistence.jdbc.PlatformAdminJdbc;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link IdempotencyStore} on {@code platform.idempotency_record}. Each call runs in its own short transaction bound
 * to the key's tenant scope (so row-level security applies) and commits immediately, so a concurrent request with the
 * same key sees the claim. Purging expired records crosses tenants and therefore uses {@link PlatformAdminJdbc}.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PlatformAdminJdbc platformAdmin;
    private final JsonMapper json;

    public JdbcIdempotencyStore(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            PlatformAdminJdbc platformAdmin,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.platformAdmin = platformAdmin;
        this.json = json;
    }

    @Override
    public Outcome begin(TenantId tenant, String key, String requestHash, Instant now, Instant expiresAt) {
        return inTenant(tenant, () -> {
            if (claim(tenant, key, requestHash, now, expiresAt)) {
                return new Started();
            }
            StoredRecord existing = find(tenant, key).orElseThrow();
            if (existing.expiresAt().toInstant().isBefore(now)) {
                delete(tenant, key);
                claim(tenant, key, requestHash, now, expiresAt);
                return new Started();
            }
            if (!existing.requestHash().equals(requestHash)) {
                return new KeyReused();
            }
            if (!"COMPLETED".equals(existing.status()) || existing.httpStatus() == null) {
                return new InProgress();
            }
            return new Replay(new StoredResponse(existing.httpStatus(), headers(existing.headers()), existing.body()));
        });
    }

    @Override
    public void complete(TenantId tenant, String key, StoredResponse response) {
        inTenant(
                tenant,
                () -> jdbc.sql("""
                        UPDATE platform.idempotency_record
                           SET status = 'COMPLETED', http_status = :status, response_body = :body,
                               response_headers = :headers
                         WHERE tenant_id = :tenantId AND idem_key = :key
                        """)
                        .param("status", response.status())
                        .param("body", response.body())
                        .param("headers", Jsonb.of(json.writeValueAsString(response.headers())))
                        .param("tenantId", tenant.value())
                        .param("key", UUID.fromString(key))
                        .update());
    }

    @Override
    public void release(TenantId tenant, String key) {
        inTenant(tenant, () -> delete(tenant, key));
    }

    @Override
    public int purgeExpired(Instant now) {
        return platformAdmin
                .jdbc()
                .sql("DELETE FROM platform.idempotency_record WHERE expires_at < :now")
                .param("now", Jsonb.utc(now))
                .update();
    }

    private boolean claim(TenantId tenant, String key, String requestHash, Instant now, Instant expiresAt) {
        return jdbc.sql("""
                                INSERT INTO platform.idempotency_record
                                       (tenant_id, idem_key, request_hash, status, created_at, expires_at)
                                VALUES (:tenantId, :key, :hash, 'IN_PROGRESS', :now, :expiresAt)
                                ON CONFLICT (tenant_id, idem_key) DO NOTHING
                                """)
                        .param("tenantId", tenant.value())
                        .param("key", UUID.fromString(key))
                        .param("hash", requestHash)
                        .param("now", Jsonb.utc(now))
                        .param("expiresAt", Jsonb.utc(expiresAt))
                        .update()
                == 1;
    }

    private Optional<StoredRecord> find(TenantId tenant, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status, http_status, response_body, response_headers::text AS headers,
                               expires_at
                          FROM platform.idempotency_record
                         WHERE tenant_id = :tenantId AND idem_key = :key
                           FOR UPDATE
                        """)
                .param("tenantId", tenant.value())
                .param("key", UUID.fromString(key))
                .query((rs, row) -> new StoredRecord(
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        (Integer) rs.getObject("http_status"),
                        rs.getString("response_body"),
                        rs.getString("headers"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    private int delete(TenantId tenant, String key) {
        return jdbc.sql("DELETE FROM platform.idempotency_record WHERE tenant_id = :tenantId AND idem_key = :key")
                .param("tenantId", tenant.value())
                .param("key", UUID.fromString(key))
                .update();
    }

    private Map<String, String> headers(@Nullable String headers) {
        return headers == null ? Map.of() : json.readValue(headers, HEADERS);
    }

    private <T> T inTenant(TenantId tenant, java.util.function.Supplier<T> work) {
        return TenantContext.callWith(tenant, () -> transactions.execute(status -> work.get()));
    }

    private record StoredRecord(
            String requestHash,
            String status,
            @Nullable Integer httpStatus,
            @Nullable String body,
            @Nullable String headers,
            OffsetDateTime expiresAt) {}
}
