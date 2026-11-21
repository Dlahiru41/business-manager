package com.bmp.iam.adapter.in.web;

import com.bmp.iam.adapter.in.web.generated.api.DevicesApi;
import com.bmp.iam.adapter.in.web.generated.model.Device;
import com.bmp.iam.adapter.in.web.generated.model.DeviceRegistration;
import com.bmp.iam.adapter.in.web.generated.model.RegisterDeviceRequest;
import com.bmp.iam.adapter.in.web.generated.model.RevokeDeviceRequest;
import com.bmp.iam.application.command.RegisterDeviceHandler;
import com.bmp.iam.application.command.RevokeDeviceHandler;
import com.bmp.iam.application.query.DeviceView;
import com.bmp.web.etag.Etags;
import com.bmp.web.idempotency.Idempotent;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Registration and revocation of tills. */
@RestController
class DevicesController implements DevicesApi {

    private final RegisterDeviceHandler registerDevice;
    private final RevokeDeviceHandler revokeDevice;
    private final IamWebMapper mapper;

    DevicesController(RegisterDeviceHandler registerDevice, RevokeDeviceHandler revokeDevice, IamWebMapper mapper) {
        this.registerDevice = registerDevice;
        this.revokeDevice = revokeDevice;
        this.mapper = mapper;
    }

    /** The one-time secret is redacted from the stored copy, so an idempotent replay never repeats it. */
    @Override
    @Idempotent(redact = {"clientSecret"})
    @PreAuthorize("hasPermission(null, 'iam:devices')")
    public ResponseEntity<DeviceRegistration> registerDevice(UUID idempotencyKey, RegisterDeviceRequest request) {
        RegisterDeviceHandler.Registration registration =
                registerDevice.handle(new RegisterDeviceHandler.RegisterDevice(
                        request.getName(), request.getLocationId(), request.getRegisterId()));
        DeviceView device = registration.device();
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Etags.of(device.version()))
                .body(new DeviceRegistration().device(mapper.toDto(device)).clientSecret(registration.clientSecret()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'iam:devices')")
    public ResponseEntity<Device> revokeDevice(UUID deviceId, UUID idempotencyKey, RevokeDeviceRequest request) {
        DeviceView revoked = revokeDevice.handle(new RevokeDeviceHandler.RevokeDevice(deviceId, request.getReason()));
        return ResponseEntity.ok().eTag(Etags.of(revoked.version())).body(mapper.toDto(revoked));
    }
}
