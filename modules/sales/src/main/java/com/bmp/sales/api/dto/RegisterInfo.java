package com.bmp.sales.api.dto;

import com.bmp.shared.id.RegisterId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A register as other modules see it (payments' drawers, sync's tills).
 *
 * @param lastNumber the highest bill sequence accepted from it (SAL-01)
 */
public record RegisterInfo(
        RegisterId id,
        String code,
        UUID locationId,
        @Nullable UUID deviceId,
        String billPrefix,
        long lastNumber,
        boolean active) {}
