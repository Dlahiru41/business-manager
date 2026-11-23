package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.DefinitionLine;
import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.inventory.domain.port.DefinitionStore;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link DefinitionStore} on {@code inventory.transformation_def_ref}; published versions never change. */
@Repository
class JdbcDefinitionStore implements DefinitionStore {

    private static final TypeReference<List<DefinitionLine>> LINES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcDefinitionStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(DefinitionRef definition) {
        jdbc.sql("""
                        INSERT INTO inventory.transformation_def_ref
                               (tenant_id, definition_id, def_version, code, name, kind, expected_yield_pct, lines, status)
                        VALUES (:tenantId, :id, :version, :code, :name, :kind, :yield, :lines, 'PUBLISHED')
                        ON CONFLICT (tenant_id, definition_id, def_version) DO NOTHING
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", definition.definitionId())
                .param("version", definition.defVersion())
                .param("code", definition.code())
                .param("name", definition.name())
                .param("kind", definition.kind())
                .param("yield", definition.expectedYieldPct())
                .param("lines", Jsonb.of(json.writeValueAsString(definition.lines())))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(UUID definitionId, int defVersion) {
        jdbc.sql("""
                        UPDATE inventory.transformation_def_ref SET status = 'RETIRED'
                         WHERE tenant_id = :tenantId AND definition_id = :id AND def_version <= :version
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", definitionId)
                .param("version", defVersion)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DefinitionRef> latest(UUID definitionId) {
        return jdbc.sql("""
                        SELECT definition_id, def_version, code, name, kind, expected_yield_pct, lines::text AS lines,
                               status
                          FROM inventory.transformation_def_ref
                         WHERE tenant_id = :tenantId AND definition_id = :id
                         ORDER BY def_version DESC
                         LIMIT 1
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", definitionId)
                .query((rs, row) -> new DefinitionRef(
                        rs.getObject("definition_id", UUID.class),
                        rs.getInt("def_version"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getBigDecimal("expected_yield_pct"),
                        json.readValue(rs.getString("lines"), LINES),
                        rs.getString("status").equals("PUBLISHED")))
                .optional();
    }
}
