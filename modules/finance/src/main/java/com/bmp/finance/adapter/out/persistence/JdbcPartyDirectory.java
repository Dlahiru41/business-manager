package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.port.PartyDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link PartyDirectory} on {@code finance.party_ref}: version-guarded upserts. */
@Repository
class JdbcPartyDirectory implements PartyDirectory {

    private final JdbcClient jdbc;

    JdbcPartyDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(PartyTerms terms, long version) {
        jdbc.sql("""
                        INSERT INTO finance.party_ref (tenant_id, party_id, type, payment_terms_days, status,
                                                       aggregate_version)
                        VALUES (:tenantId, :partyId, :type, :terms, :status, :version)
                        ON CONFLICT (tenant_id, party_id) DO UPDATE
                           SET type = EXCLUDED.type, payment_terms_days = EXCLUDED.payment_terms_days,
                               status = EXCLUDED.status, aggregate_version = EXCLUDED.aggregate_version
                         WHERE finance.party_ref.aggregate_version < EXCLUDED.aggregate_version
                        """)
                .param("tenantId", Tenants.current())
                .param("partyId", terms.partyId())
                .param("type", terms.type())
                .param("terms", terms.paymentTermsDays())
                .param("status", terms.status())
                .param("version", version)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PartyTerms> find(UUID partyId) {
        return jdbc.sql("""
                        SELECT party_id, type, payment_terms_days, status FROM finance.party_ref
                         WHERE tenant_id = :tenantId AND party_id = :partyId
                        """)
                .param("tenantId", Tenants.current())
                .param("partyId", partyId)
                .query((rs, row) -> new PartyTerms(
                        rs.getObject("party_id", UUID.class),
                        rs.getString("type"),
                        rs.getInt("payment_terms_days"),
                        rs.getString("status")))
                .optional();
    }
}
