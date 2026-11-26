package com.bmp.iam.api.dto;

import com.bmp.shared.id.DeviceId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A device, its register and location.
 *
 * @param registerId the register the device bills on; {@code null} for a device not bound to one
 */
public record DeviceBinding(DeviceId deviceId, @Nullable UUID registerId, UUID locationId, boolean active) {}
