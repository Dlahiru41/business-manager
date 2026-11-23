package com.bmp.party.domain.port;

import com.bmp.party.domain.model.CustomerGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Customer groups of the current tenant. */
public interface CustomerGroupRepository {

    Optional<CustomerGroup> findById(UUID id);

    /** A page of groups by code, after {@code afterCode}. */
    List<CustomerGroup> findPage(@Nullable String afterCode, int limit);

    CustomerGroup saveAndFlush(CustomerGroup group);
}
