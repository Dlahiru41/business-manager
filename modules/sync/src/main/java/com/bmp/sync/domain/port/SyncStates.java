package com.bmp.sync.domain.port;

import com.bmp.sync.domain.model.DeviceState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Device states and reported number gaps of the current tenant (SYN-06, SYN-07). */
public interface SyncStates {

    Optional<DeviceState> device(UUID deviceId);

    /** Records a bootstrap or an upload and the clock skew seen, setting or clearing the CLOCK_SKEW flag. */
    void seen(UUID deviceId, UUID registerId, boolean bootstrap, @Nullable Long clockSkewSeconds, Instant at);

    Set<Long> reportedGaps(UUID registerId);

    void reportGaps(UUID registerId, List<Long> sequences, Instant at);
}
