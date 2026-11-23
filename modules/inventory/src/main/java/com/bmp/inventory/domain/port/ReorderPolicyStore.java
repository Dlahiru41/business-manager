package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.ReorderPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Reorder levels (INV-13). */
public interface ReorderPolicyStore {

    /** The policy of a variant at a location, locked until the transaction ends. */
    Optional<ReorderPolicy> lock(UUID variantId, UUID locationId);

    /** Creates or replaces a policy; returns it with its new version. */
    ReorderPolicy save(ReorderPolicy policy);

    void flag(UUID variantId, UUID locationId, boolean low);

    /** Policies at or below their level, optionally of one location. */
    List<ReorderPolicy> low(@Nullable UUID locationId);
}
