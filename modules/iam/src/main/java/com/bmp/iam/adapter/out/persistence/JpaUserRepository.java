package com.bmp.iam.adapter.out.persistence;

import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.port.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaUserRepository extends JpaRepository<AppUser, UUID>, UserRepository {

    @Override
    @Query("""
            select count(distinct u.id) from AppUser u join u.roles g
             where g.roleId = :roleId and u.status = com.bmp.iam.domain.model.UserStatus.ACTIVE
            """)
    long countActiveHolders(UUID roleId);

    @Query("""
            select u from AppUser u
             where u.audit.createdAt > :createdAt or (u.audit.createdAt = :createdAt and u.id > :id)
             order by u.audit.createdAt, u.id
            """)
    List<AppUser> page(Instant createdAt, UUID id, Limit limit);

    @Override
    default List<AppUser> findPage(Instant afterCreatedAt, UUID afterId, int limit) {
        return page(afterCreatedAt, afterId, Limit.of(limit));
    }
}
