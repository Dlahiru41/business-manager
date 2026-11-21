package com.bmp.tenant.adapter.out.persistence;

import com.bmp.tenant.domain.model.Tenant;
import com.bmp.tenant.domain.port.TenantRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaTenantRepository extends JpaRepository<Tenant, UUID>, TenantRepository {}
