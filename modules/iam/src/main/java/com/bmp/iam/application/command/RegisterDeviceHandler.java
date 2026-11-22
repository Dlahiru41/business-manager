package com.bmp.iam.application.command;

import com.bmp.iam.application.query.DeviceView;
import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.iam.domain.port.IdentityProviderGateway.DeviceCredential;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewDeviceClient;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.RegisterId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.Sensitive;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers a till: creates its confidential client in the identity provider (outside any transaction), stores the
 * device and publishes {@code DeviceRegistered}. The secret is returned to the caller once and stored nowhere; the
 * client is deleted again if the local transaction fails.
 */
@Service
public class RegisterDeviceHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterDeviceHandler.class);

    private final DeviceRepository devices;
    private final RoleAssignments assignments;
    private final IdentityProviderGateway identityProvider;
    private final ApplicationEventPublisher events;
    private final EventStampSource stamps;
    private final IdGenerator ids;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public RegisterDeviceHandler(
            DeviceRepository devices,
            RoleAssignments assignments,
            IdentityProviderGateway identityProvider,
            ApplicationEventPublisher events,
            EventStampSource stamps,
            IdGenerator ids,
            PlatformTransactionManager transactionManager) {
        this.devices = devices;
        this.assignments = assignments;
        this.identityProvider = identityProvider;
        this.events = events;
        this.stamps = stamps;
        this.ids = ids;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * A till to register.
     *
     * @param registerId the billing counter it serves, if already known
     */
    public record RegisterDevice(
            String name, UUID locationId, @Nullable UUID registerId) {}

    /** The stored device and its one-time secret. */
    @Sensitive
    public record Registration(DeviceView device, String clientSecret) {

        @Override
        public String toString() {
            return "Registration[device=" + device.id() + ", clientSecret=***]";
        }
    }

    @Observed(name = "bmp.iam.register-device")
    public Registration handle(RegisterDevice command) {
        TenantId tenant = TenantContext.require();
        readOnly.executeWithoutResult(status -> assignments.requireActiveLocations(List.of(command.locationId())));

        DeviceId id = ids.next(DeviceId::new);
        DeviceCredential credential =
                identityProvider.registerDevice(new NewDeviceClient(tenant, id, Device.clientIdOf(id), command.name()));
        DeviceView registered;
        try {
            registered = Objects.requireNonNull(transaction.execute(status -> {
                UUID register = command.registerId();
                Device device = Device.register(
                        id,
                        tenant,
                        command.name(),
                        LocationId.of(command.locationId()),
                        register != null ? RegisterId.of(register) : null,
                        stamps);
                Device saved = devices.saveAndFlush(device);
                device.pullEvents().forEach(events::publishEvent);
                return DeviceView.of(saved);
            }));
        } catch (RuntimeException e) {
            Compensation.run(
                    "delete-device-client",
                    credential.clientId(),
                    () -> identityProvider.deleteDevice(credential.clientId()));
            throw e;
        }
        log.atInfo()
                .addKeyValue("registeredDeviceId", id.toString())
                .log("Device registered deviceId={} locationId={}", id, command.locationId());
        return new Registration(registered, credential.clientSecret());
    }
}
