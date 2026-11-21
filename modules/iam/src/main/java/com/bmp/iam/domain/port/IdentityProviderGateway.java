package com.bmp.iam.domain.port;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.Sensitive;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The identity provider (Keycloak) as seen by the iam module: accounts, their tenant and roles, and device clients.
 * Implementations apply timeouts, retry only idempotent calls, use a circuit breaker and throw
 * {@code IntegrationException(IDENTITY_PROVIDER_UNAVAILABLE)} when the provider cannot be reached. Callers never invoke
 * the gateway inside a database transaction and compensate when their own transaction fails afterwards.
 */
public interface IdentityProviderGateway {

    /**
     * Creates an enabled account that must set its password on first sign-in (an e-mail is sent when an address is
     * given). Throws {@code USERNAME_TAKEN} when the username exists.
     *
     * @return the account's subject, which becomes the user id
     */
    UserId createUser(NewIdentity identity);

    /** Removes an account created by {@link #createUser}; used only to compensate a failed local transaction. */
    void deleteUser(UserId subject);

    /** Replaces the tenant role codes carried in the account's tokens. */
    void setRoles(UserId subject, List<String> roleCodes);

    /** Enables or disables the account; disabling also ends its sessions. */
    void setEnabled(UserId subject, boolean enabled);

    /** Creates the confidential client of a device, with a freshly generated secret that is returned once. */
    DeviceCredential registerDevice(NewDeviceClient device);

    /** Removes a device client created by {@link #registerDevice}; used only to compensate. */
    void deleteDevice(String clientId);

    /** Disables the device client so that it can obtain no new token. Idempotent. */
    void disableDevice(String clientId);

    /**
     * A new account.
     *
     * @param username sign-in name, unique in the identity provider
     * @param email optional; personal data held by the identity provider only
     * @param roleCodes tenant role codes for the {@code roles} claim
     */
    @Sensitive
    record NewIdentity(
            String username, @Nullable String email, String displayName, TenantId tenant, List<String> roleCodes) {

        public NewIdentity {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(tenant, "tenant");
            roleCodes = List.copyOf(roleCodes);
        }

        @Override
        public String toString() {
            return "NewIdentity[username=***, email=***, displayName=***, tenant=" + tenant + ", roleCodes=" + roleCodes
                    + "]";
        }
    }

    /**
     * A device client to create; tokens of the client carry the tenant, the device and the {@code DEVICE} role.
     *
     * @param clientId the client id, {@code device-<deviceId>}
     * @param name shown in the identity provider console
     */
    record NewDeviceClient(TenantId tenant, DeviceId device, String clientId, String name) {

        public NewDeviceClient {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(name, "name");
        }
    }

    /** The client credential of a device, shown to the caller once and never stored. */
    @Sensitive
    record DeviceCredential(String clientId, String clientSecret) {

        public DeviceCredential {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(clientSecret, "clientSecret");
        }

        @Override
        public String toString() {
            return "DeviceCredential[clientId=" + clientId + ", clientSecret=***]";
        }
    }
}
