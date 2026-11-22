package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.shared.context.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CatalogRevisions} on {@code catalog.catalog_revision}; the upsert locks the tenant row until commit. */
@Repository
class JdbcCatalogRevisions implements CatalogRevisions {

    private final JdbcClient jdbc;

    JdbcCatalogRevisions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next() {
        return jdbc.sql("""
                        INSERT INTO catalog.catalog_revision (tenant_id, revision) VALUES (:tenantId, 1)
                        ON CONFLICT (tenant_id) DO UPDATE SET revision = catalog.catalog_revision.revision + 1
                        RETURNING revision
                        """)
                .param("tenantId", TenantContext.require().value())
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long current() {
        return jdbc.sql("SELECT revision FROM catalog.catalog_revision WHERE tenant_id = :tenantId")
                .param("tenantId", TenantContext.require().value())
                .query(Long.class)
                .optional()
                .orElse(0L);
    }
}
