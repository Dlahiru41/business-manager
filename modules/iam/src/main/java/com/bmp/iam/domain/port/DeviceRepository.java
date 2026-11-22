package com.bmp.iam.domain.port;

import com.bmp.iam.domain.model.Device;
import java.util.Optional;
import java.util.UUID;

/** Devices of the current tenant. */
public interface DeviceRepository {

    Optional<Device> findById(UUID id);

    Device save(Device device);

    /** Saves and flushes, so the returned entity carries its new version. */
    Device saveAndFlush(Device device);
}
