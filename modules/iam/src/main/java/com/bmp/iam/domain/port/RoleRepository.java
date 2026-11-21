package com.bmp.iam.domain.port;

import com.bmp.iam.domain.model.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Roles of the current tenant. */
public interface RoleRepository {

    Optional<Role> findById(UUID id);

    Optional<Role> findByCode(String code);

    List<Role> findByCodeIn(Collection<String> codes);

    List<Role> findByIdIn(Collection<UUID> ids);

    /** Every role, system roles first, then by code. */
    List<Role> findAllOrdered();

    /**
     * Reads the role with a row lock held until the transaction ends. Locking the owner role serializes every change
     * that may remove an owner (IAM-01).
     */
    Optional<Role> lockByCode(String code);

    Role save(Role role);

    /** Saves and flushes, so the returned entity carries its new version. */
    Role saveAndFlush(Role role);
}
