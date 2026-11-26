package com.bmp.sync.application;

import com.bmp.iam.api.IamQueries;
import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.RegisterId;
import com.bmp.sync.domain.error.SyncErrorCode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Rule SYN-01: only devices sync, each active and bound to an active register whose location applies to everything. */
@Component
public class SyncDevices {

    private final IamQueries iam;
    private final SalesQueries sales;

    public SyncDevices(IamQueries iam, SalesQueries sales) {
        this.iam = iam;
        this.sales = sales;
    }

    /** The calling till: its device, register and the register's location and numbering. */
    public record Till(
            UUID deviceId, UUID registerId, UUID locationId, String registerCode, String billPrefix, long lastNumber) {}

    @Transactional(readOnly = true)
    public Till current() {
        DeviceId device = ActorContext.current()
                .map(Actor::deviceId)
                .orElseThrow(() -> new BusinessRuleViolation(SyncErrorCode.DEVICE_REQUIRED, Map.of()));
        DeviceBinding binding = iam.findDevice(device)
                .filter(DeviceBinding::active)
                .filter(b -> b.registerId() != null)
                .orElseThrow(() -> notBound(device));
        RegisterInfo register = sales.findRegister(RegisterId.of(Objects.requireNonNull(binding.registerId())))
                .filter(RegisterInfo::active)
                .orElseThrow(() -> notBound(device));
        return new Till(
                device.value(),
                register.id().value(),
                register.locationId(),
                register.code(),
                register.billPrefix(),
                register.lastNumber());
    }

    private static BusinessRuleViolation notBound(DeviceId device) {
        return new BusinessRuleViolation(SyncErrorCode.DEVICE_NOT_BOUND, Map.of("deviceId", device.value()));
    }
}
