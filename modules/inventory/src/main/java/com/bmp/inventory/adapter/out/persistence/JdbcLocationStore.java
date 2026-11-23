package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.LocationRef;
import com.bmp.inventory.domain.port.LocationStore;
import com.bmp.shared.context.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link LocationStore} on {@code inventory.location_ref}. */
@Repository
class JdbcLocationStore implements LocationStore {

    private final JdbcClient jdbc;

    JdbcLocationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(LocationRef location) {
        jdbc.sql("""
                        INSERT INTO inventory.location_ref (tenant_id, location_id, name, type, status)
                        VALUES (:tenantId, :id, :name, :type, :status)
                        ON CONFLICT (tenant_id, location_id) DO UPDATE
                           SET name = EXCLUDED.name, type = EXCLUDED.type, status = EXCLUDED.status
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", location.locationId())
                .param("name", location.name())
                .param("type", location.type())
                .param("status", location.active() ? "ACTIVE" : "INACTIVE")
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LocationRef> find(UUID locationId) {
        return jdbc.sql("""
                        SELECT location_id, name, type, status FROM inventory.location_ref
                         WHERE tenant_id = :tenantId AND location_id = :id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", locationId)
                .query((rs, row) -> new LocationRef(
                        rs.getObject("location_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("status").equals("ACTIVE")))
                .optional();
    }
}
