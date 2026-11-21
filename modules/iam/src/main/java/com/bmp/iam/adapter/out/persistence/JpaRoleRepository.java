package com.bmp.iam.adapter.out.persistence;

import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.port.RoleRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaRoleRepository extends JpaRepository<Role, UUID>, RoleRepository {

    @Override
    @Query("select r from Role r order by r.system desc, r.code")
    List<Role> findAllOrdered();

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    Optional<Role> lockByCode(String code);
}
