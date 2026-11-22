package com.bmp.pricing.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.pricing.domain.port.PriceListVersionLog;
import com.bmp.shared.context.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link PriceListVersionLog} on the append-only {@code pricing.price_list_version}. */
@Repository
class JdbcPriceListVersionLog implements PriceListVersionLog {

    private final JdbcClient jdbc;

    JdbcPriceListVersionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            UUID priceListId,
            int version,
            Instant publishedAt,
            @Nullable UUID publishedBy,
            @Nullable String note,
            int changeCount,
            String changesJson) {
        jdbc.sql("""
                        INSERT INTO pricing.price_list_version
                               (tenant_id, price_list_id, version, published_at, published_by, note, change_count,
                                change_summary)
                        VALUES (:tenantId, :listId, :version, :publishedAt, :publishedBy, :note, :changeCount, :changes)
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("listId", priceListId)
                .param("version", version)
                .param("publishedAt", Jsonb.utc(publishedAt))
                .param("publishedBy", publishedBy)
                .param("note", note)
                .param("changeCount", changeCount)
                .param("changes", Jsonb.of(changesJson))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Entry> findPage(UUID priceListId, @Nullable Integer beforeVersion, int limit) {
        return jdbc.sql("""
                        SELECT price_list_id, version, published_at, published_by, note, change_count,
                               change_summary::text AS changes
                          FROM pricing.price_list_version
                         WHERE tenant_id = :tenantId AND price_list_id = :listId
                           AND (CAST(:before AS integer) IS NULL OR version < :before)
                         ORDER BY version DESC
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("listId", priceListId)
                .param("before", beforeVersion)
                .param("limit", limit)
                .query(JdbcPriceListVersionLog::entry)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Entry> find(UUID priceListId, int version) {
        return jdbc.sql("""
                        SELECT price_list_id, version, published_at, published_by, note, change_count,
                               change_summary::text AS changes
                          FROM pricing.price_list_version
                         WHERE tenant_id = :tenantId AND price_list_id = :listId AND version = :version
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("listId", priceListId)
                .param("version", version)
                .query(JdbcPriceListVersionLog::entry)
                .optional();
    }

    private static Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(
                rs.getObject("price_list_id", UUID.class),
                rs.getInt("version"),
                Jsonb.instant(rs.getTimestamp("published_at")),
                rs.getObject("published_by", UUID.class),
                rs.getString("note"),
                rs.getInt("change_count"),
                rs.getString("changes"));
    }
}
