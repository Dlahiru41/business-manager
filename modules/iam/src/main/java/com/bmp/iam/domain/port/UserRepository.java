package com.bmp.iam.domain.port;

import com.bmp.iam.domain.model.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Users of the current tenant. */
public interface UserRepository {

    Optional<AppUser> findById(UUID id);

    AppUser save(AppUser user);

    /** Saves and flushes, so the returned entity carries its new version. */
    AppUser saveAndFlush(AppUser user);

    /** Active users holding the role (IAM-01 counts owners with it). */
    long countActiveHolders(UUID roleId);

    /** Keyset page ordered by creation time then id, starting after the given position. */
    List<AppUser> findPage(Instant afterCreatedAt, UUID afterId, int limit);
}
