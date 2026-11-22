package com.bmp.tenant.adapter.out.persistence;

import com.bmp.tenant.domain.model.Tenant;
import com.bmp.tenant.domain.port.TenantRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaTenantRepository extends JpaRepository<Tenant, UUID>, TenantRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> lockById(UUID id);
}
