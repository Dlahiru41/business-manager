package com.bmp.sync.domain.service;

import java.time.Duration;
import java.time.Instant;

/** Rule SYN-07: how far a device's clock is from the server's; beyond five minutes the device is flagged. */
public final class ClockSkew {

    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private ClockSkew() {}

    /** Seconds the device is ahead (positive) or behind (negative). */
    public static long seconds(Instant deviceTime, Instant serverTime) {
        return Duration.between(serverTime, deviceTime).toSeconds();
    }

    public static boolean flagged(long skewSeconds) {
        return Math.abs(skewSeconds) > TOLERANCE.toSeconds();
    }
}
