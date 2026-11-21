package com.bmp.web.problem;

import com.bmp.shared.error.ErrorCode;
import java.util.Objects;

/**
 * Maps a database constraint name to a domain error code, e.g. {@code uk_variant_tenant_sku} to
 * {@code SKU_ALREADY_EXISTS}. Modules contribute these as beans; unknown constraint violations become 500.
 *
 * @param constraintName the constraint name as declared in the migration
 * @param errorCode the code reported to the client
 */
public record ConstraintErrorMapping(String constraintName, ErrorCode errorCode) {

    public ConstraintErrorMapping {
        Objects.requireNonNull(constraintName, "constraintName");
        Objects.requireNonNull(errorCode, "errorCode");
    }

    public static ConstraintErrorMapping of(String constraintName, ErrorCode errorCode) {
        return new ConstraintErrorMapping(constraintName, errorCode);
    }
}
