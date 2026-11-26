package com.bmp.sync.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What sync knows of a device: its last bootstrap and upload, its clock skew and flags (SYN-07). */
public record DeviceState(
        UUID deviceId,
        UUID registerId,
        @Nullable Instant lastBootstrapAt,
        @Nullable Instant lastUploadAt,
        @Nullable Long clockSkewSeconds,
        List<String> flags) {

    public DeviceState {
        flags = List.copyOf(flags);
    }
}
