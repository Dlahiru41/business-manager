package com.bmp.shared.error;

import java.io.Serial;
import java.util.Map;

/** The client's {@code If-Match} version is stale (412); the problem body carries the current version. */
public final class VersionConflict extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long currentVersion;

    public VersionConflict(long currentVersion) {
        super(PlatformErrorCode.VERSION_CONFLICT, Map.of("currentVersion", currentVersion));
        this.currentVersion = currentVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
