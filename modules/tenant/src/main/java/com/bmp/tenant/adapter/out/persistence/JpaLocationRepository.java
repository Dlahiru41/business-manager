package com.bmp.tenant.adapter.out.persistence;

import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.port.LocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaLocationRepository extends JpaRepository<Location, UUID>, LocationRepository {

    @Query("""
            select l from Location l
             where l.audit.createdAt > :createdAt or (l.audit.createdAt = :createdAt and l.id > :id)
             order by l.audit.createdAt, l.id
            """)
    List<Location> page(Instant createdAt, UUID id, Limit limit);

    @Override
    default List<Location> findPage(Instant afterCreatedAt, UUID afterId, int limit) {
        return page(afterCreatedAt, afterId, Limit.of(limit));
    }
}
