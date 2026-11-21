package com.bmp.iam.application.command;

import com.bmp.iam.application.DeviceStatusLookup;
import com.bmp.iam.application.query.DeviceView;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Revokes a till (IAM-03). Its client is disabled in the identity provider first, so it obtains no new token; then the
 * device is marked revoked and evicted from the status cache, so its current tokens are rejected at once on this
 * instance and within 60 seconds on the others. Disabling is idempotent, so a failed revocation can be retried.
 */
@Service
public class RevokeDeviceHandler {

    private static final Logger log = LoggerFactory.getLogger(RevokeDeviceHandler.class);

    private final DeviceRepository devices;
    private final DeviceStatusLookup deviceStatus;
    private final IdentityProviderGateway identityProvider;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public RevokeDeviceHandler(
            DeviceRepository devices,
            DeviceStatusLookup deviceStatus,
            IdentityProviderGateway identityProvider,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.devices = devices;
        this.deviceStatus = deviceStatus;
        this.identityProvider = identityProvider;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * A device to revoke.
     *
     * @param reason why, for the audit trail
     */
    public record RevokeDevice(UUID id, String reason) {}

    @Observed(name = "bmp.iam.revoke-device")
    public DeviceView handle(RevokeDevice command) {
        TenantId tenant = TenantContext.require();
        String clientId = Objects.requireNonNull(readOnly.execute(status -> {
            Device device = load(command.id());
            if (!device.isActive()) {
                throw new BusinessRuleViolation(IamErrorCode.DEVICE_ALREADY_REVOKED, Map.of("deviceId", command.id()));
            }
            return device.idpClientId();
        }));

        identityProvider.disableDevice(clientId);
        DeviceView revoked = Objects.requireNonNull(transaction.execute(status -> {
            Device device = load(command.id());
            device.revoke(command.reason(), clock.instant());
            return DeviceView.of(devices.saveAndFlush(device));
        }));
        deviceStatus.evict(tenant, DeviceId.of(command.id()));
        log.atInfo()
                .addKeyValue("revokedDeviceId", command.id().toString())
                .log("Device revoked deviceId={}", command.id());
        return revoked;
    }

    private Device load(UUID id) {
        return devices.findById(id)
                .orElseThrow(() -> new NotFoundException(IamErrorCode.DEVICE_NOT_FOUND, Map.of("deviceId", id)));
    }
}
