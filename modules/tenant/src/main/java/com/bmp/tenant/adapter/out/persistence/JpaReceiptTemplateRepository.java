package com.bmp.tenant.adapter.out.persistence;

import com.bmp.tenant.domain.model.ReceiptTemplate;
import com.bmp.tenant.domain.port.ReceiptTemplateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaReceiptTemplateRepository extends JpaRepository<ReceiptTemplate, UUID>, ReceiptTemplateRepository {

    Optional<ReceiptTemplate> findByIsDefaultTrue();

    @Override
    default Optional<ReceiptTemplate> findDefault() {
        return findByIsDefaultTrue();
    }

    @Query("""
            select t from ReceiptTemplate t
             where t.audit.createdAt > :createdAt or (t.audit.createdAt = :createdAt and t.id > :id)
             order by t.audit.createdAt, t.id
            """)
    List<ReceiptTemplate> page(Instant createdAt, UUID id, Limit limit);

    @Override
    default List<ReceiptTemplate> findPage(Instant afterCreatedAt, UUID afterId, int limit) {
        return page(afterCreatedAt, afterId, Limit.of(limit));
    }
}
