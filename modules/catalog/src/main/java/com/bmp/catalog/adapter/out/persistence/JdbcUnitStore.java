package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.catalog.domain.port.UnitRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UnitRepository} on {@code catalog.unit_of_measure}. Not a JPA entity: standard units have no tenant, and
 * Hibernate's tenant filter would hide them; row-level security lets them through for every tenant (CAT-01).
 */
@Repository
class JdbcUnitStore implements UnitRepository {

    private final JdbcClient jdbc;

    JdbcUnitStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UnitOfMeasure> findAll() {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, name, dimension, to_si_factor FROM catalog.unit_of_measure
                         ORDER BY tenant_id IS NOT NULL, lower(code), id
                        """).query(JdbcUnitStore::unit).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UnitOfMeasure> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, name, dimension, to_si_factor FROM catalog.unit_of_measure
                         WHERE id = :id
                        """).param("id", id).query(JdbcUnitStore::unit).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UnitOfMeasure> findByCode(String code) {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, name, dimension, to_si_factor FROM catalog.unit_of_measure
                         WHERE lower(code) = :code ORDER BY tenant_id IS NOT NULL
                        """).param("code", code.toLowerCase(Locale.ROOT)).query(JdbcUnitStore::unit).list().stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UnitOfMeasure> findPage(@Nullable Dimension dimension, @Nullable PageKey after, int limit) {
        // The sort key is "<0 standard | 1 own>:<lower-case code>", matching the ORDER BY below.
        String afterGroup = after != null ? after.sortKey().substring(0, 1) : "";
        String afterCode = after != null ? after.sortKey().substring(2) : "";
        return jdbc.sql("""
                        SELECT id, tenant_id, code, name, dimension, to_si_factor FROM catalog.unit_of_measure
                         WHERE (CAST(:dimension AS text) IS NULL OR dimension = :dimension)
                           AND (CAST(:afterId AS uuid) IS NULL
                                OR ((tenant_id IS NOT NULL)::int, lower(code), id)
                                   > (CAST(:afterGroup AS int), :afterCode, CAST(:afterId AS uuid)))
                         ORDER BY tenant_id IS NOT NULL, lower(code), id
                         LIMIT :limit
                        """)
                .param("dimension", dimension != null ? dimension.name() : null)
                .param("afterId", after != null ? after.id() : null)
                .param("afterGroup", after != null ? afterGroup : "0")
                .param("afterCode", afterCode)
                .param("limit", limit)
                .query(JdbcUnitStore::unit)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UnitOfMeasure unit) {
        jdbc.sql("""
                        INSERT INTO catalog.unit_of_measure (id, tenant_id, code, name, dimension, to_si_factor)
                        VALUES (:id, :tenantId, :code, :name, :dimension, :factor)
                        """)
                .param("id", unit.id())
                .param("tenantId", unit.tenantId())
                .param("code", unit.code())
                .param("name", unit.name())
                .param("dimension", unit.dimension().name())
                .param("factor", unit.toSiFactor())
                .update();
    }

    /** The sort key of a unit for keyset pages: standard units first, then by lower-case code. */
    static String sortKey(UnitOfMeasure unit) {
        return (unit.isStandard() ? "0:" : "1:") + unit.code().toLowerCase(Locale.ROOT);
    }

    private static UnitOfMeasure unit(ResultSet rs, int row) throws SQLException {
        return new UnitOfMeasure(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                Dimension.valueOf(rs.getString("dimension")),
                rs.getBigDecimal("to_si_factor"));
    }
}
