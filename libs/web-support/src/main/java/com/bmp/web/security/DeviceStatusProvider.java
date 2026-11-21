package com.bmp.web.security;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;

/**
 * Answers whether a device token may still be used (SPI implemented by the {@code iam} module; IAM-03: revoked devices
 * are rejected within the cache lifetime of at most 60 s). The default implementation knows no device: fail closed.
 */
@FunctionalInterface
public interface DeviceStatusProvider {

    boolean isActive(TenantId tenant, DeviceId device);
}
