package com.bmp.iam.application.query;

import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.model.DeviceStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read model of a device; never contains its credential. */
public record DeviceView(
        UUID id,
        String name,
        UUID locationId,
        @Nullable UUID registerId,
        String clientId,
        DeviceStatus status,
        Instant registeredAt,
        @Nullable Instant revokedAt,
        long version) {

    public static DeviceView of(Device device) {
        return new DeviceView(
                device.id().value(),
                device.name(),
                device.locationId(),
                device.registerId(),
                device.idpClientId(),
                device.status(),
                device.registeredAt(),
                device.revokedAt(),
                device.version());
    }
}
