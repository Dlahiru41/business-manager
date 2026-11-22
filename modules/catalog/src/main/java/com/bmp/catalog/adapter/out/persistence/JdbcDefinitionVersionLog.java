package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.port.DefinitionVersionLog;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link DefinitionVersionLog} on the append-only {@code catalog.transformation_definition_version}. */
@Repository
class JdbcDefinitionVersionLog implements DefinitionVersionLog {

    private final JdbcClient jdbc;

    JdbcDefinitionVersionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            UUID definitionId, int version, Instant publishedAt, @Nullable UUID publishedBy, String snapshotJson) {
        jdbc.sql("""
                        INSERT INTO catalog.transformation_definition_version
                               (tenant_id, definition_id, def_version, published_at, published_by, snapshot)
                        VALUES (:tenantId, :definitionId, :version, :publishedAt, :publishedBy, :snapshot)
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("definitionId", definitionId)
                .param("version", version)
                .param("publishedAt", Jsonb.utc(publishedAt))
                .param("publishedBy", publishedBy)
                .param("snapshot", Jsonb.of(snapshotJson))
                .update();
    }
}
