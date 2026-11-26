package com.bmp.iam.api;

import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import java.util.Optional;

/** What other modules may ask iam about the current tenant's users and devices. */
public interface IamQueries {

    /**
     * Whether an active user of the tenant holds the permission through an active role: a manager approving a
     * discount, a void or a credit sale at the till (SAL-05, SAL-07, SAL-09, SAL-10).
     */
    boolean userHasPermission(UserId user, String permission);

    /** The device and what it is bound to (SYN-01). */
    Optional<DeviceBinding> findDevice(DeviceId device);
}
