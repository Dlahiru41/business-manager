package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.BatchInfo;
import com.bmp.inventory.domain.port.BatchStore;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link BatchStore} on {@code inventory.batch}. */
@Repository
class JdbcBatchStore implements BatchStore {

    private static final String COLUMNS = "id, variant_id, code, received_at, expires_on";

    private final JdbcClient jdbc;
    private final IdGenerator ids;

    JdbcBatchStore(JdbcClient jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BatchInfo findOrCreate(
            UUID variantId, String code, @Nullable LocalDate expiresOn, Instant receivedAt, @Nullable UUID supplierId) {
        UUID tenant = TenantContext.require().value();
        jdbc.sql("""
                        INSERT INTO inventory.batch (tenant_id, id, variant_id, code, received_at, expires_on, supplier_id)
                        VALUES (:tenantId, :id, :variantId, :code, :receivedAt, :expiresOn, :supplierId)
                        ON CONFLICT (tenant_id, variant_id, code) DO NOTHING
                        """)
                .param("tenantId", tenant)
                .param("id", ids.newUuid())
                .param("variantId", variantId)
                .param("code", code)
                .param("receivedAt", Jsonb.utc(receivedAt))
                .param("expiresOn", expiresOn)
                .param("supplierId", supplierId)
                .update();
        return jdbc.sql(
                        "SELECT " + COLUMNS
                                + " FROM inventory.batch WHERE tenant_id = :tenantId AND variant_id = :variantId AND code = :code")
                .param("tenantId", tenant)
                .param("variantId", variantId)
                .param("code", code)
                .query(JdbcBatchStore::batch)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BatchInfo> find(UUID batchId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM inventory.batch WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", TenantContext.require().value())
                .param("id", batchId)
                .query(JdbcBatchStore::batch)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, BatchInfo> findAll(Collection<UUID> batchIds) {
        Map<UUID, BatchInfo> batches = new HashMap<>();
        if (batchIds.isEmpty()) {
            return batches;
        }
        jdbc.sql("SELECT " + COLUMNS + " FROM inventory.batch WHERE tenant_id = :tenantId AND id IN (:ids)")
                .param("tenantId", TenantContext.require().value())
                .param("ids", List.copyOf(batchIds))
                .query(JdbcBatchStore::batch)
                .list()
                .forEach(b -> batches.put(b.id(), b));
        return batches;
    }

    private static BatchInfo batch(ResultSet rs, int row) throws SQLException {
        Date expires = rs.getDate("expires_on");
        return new BatchInfo(
                rs.getObject("id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getString("code"),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("received_at"))),
                expires == null ? null : expires.toLocalDate());
    }
}
