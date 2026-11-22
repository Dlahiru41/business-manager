package com.bmp.shared.context;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting: a user, a device (till), both (a cashier on a till), or neither (the system itself, e.g. a
 * scheduled job or an event listener without actor metadata).
 *
 * @param userId the acting user, if any
 * @param deviceId the acting device, if any
 */
public record Actor(@Nullable UserId userId, @Nullable DeviceId deviceId) {

    private static final Actor SYSTEM = new Actor(null, null);

    public static Actor system() {
        return SYSTEM;
    }

    public static Actor user(UserId userId) {
        return new Actor(userId, null);
    }

    public boolean isSystem() {
        return userId == null && deviceId == null;
    }
}
