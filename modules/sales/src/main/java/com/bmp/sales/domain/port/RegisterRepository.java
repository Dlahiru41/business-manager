package com.bmp.sales.domain.port;

import com.bmp.sales.domain.model.Register;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Registers of the current tenant. */
public interface RegisterRepository {

    Optional<Register> findById(UUID id);

    /** The register, locked until the transaction ends (its last number moves on). */
    Optional<Register> lock(UUID id);

    List<Register> findPage(@Nullable UUID locationId, @Nullable String afterCode, int limit);

    Register saveAndFlush(Register register);
}
