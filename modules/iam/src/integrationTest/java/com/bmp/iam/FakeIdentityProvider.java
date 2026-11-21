package com.bmp.iam;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.IntegrationException;
import com.bmp.shared.id.UserId;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * In-memory identity provider for the iam API tests: keeps accounts and device clients, and fails on demand to
 * exercise unavailability and compensation.
 */
public class FakeIdentityProvider implements IdentityProviderGateway {

    /** An account as the identity provider would hold it. */
    public record Account(
            String username, @Nullable String email, String tenant, List<String> roles, boolean enabled) {}

    /** A device client as the identity provider would hold it. */
    public record Client(String clientId, String tenant, String device, String secret, boolean enabled) {}

    private final Map<UserId, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private volatile @Nullable UserId nextSubject;

    @Override
    public UserId createUser(NewIdentity identity) {
        failIfRequested("create-user");
        boolean taken = accounts.values().stream().anyMatch(a -> a.username().equals(identity.username()));
        if (taken) {
            throw new BusinessRuleViolation(IamErrorCode.USERNAME_TAKEN, Map.of());
        }
        UserId forced = nextSubject;
        nextSubject = null;
        UserId subject = forced != null ? forced : UserId.of(UUID.randomUUID());
        accounts.put(
                subject,
                new Account(
                        identity.username(),
                        identity.email(),
                        identity.tenant().toString(),
                        identity.roleCodes(),
                        true));
        return subject;
    }

    @Override
    public void deleteUser(UserId subject) {
        failIfRequested("delete-user");
        accounts.remove(subject);
    }

    @Override
    public void setRoles(UserId subject, List<String> roleCodes) {
        failIfRequested("set-roles");
        accounts.computeIfPresent(
                subject,
                (id, a) -> new Account(a.username(), a.email(), a.tenant(), List.copyOf(roleCodes), a.enabled()));
    }

    @Override
    public void setEnabled(UserId subject, boolean enabled) {
        failIfRequested("set-enabled");
        accounts.computeIfPresent(
                subject, (id, a) -> new Account(a.username(), a.email(), a.tenant(), a.roles(), enabled));
    }

    @Override
    public DeviceCredential registerDevice(NewDeviceClient device) {
        failIfRequested("register-device");
        String secret = "secret-" + UUID.randomUUID();
        clients.put(
                device.clientId(),
                new Client(
                        device.clientId(),
                        device.tenant().toString(),
                        device.device().toString(),
                        secret,
                        true));
        return new DeviceCredential(device.clientId(), secret);
    }

    @Override
    public void deleteDevice(String clientId) {
        failIfRequested("delete-device");
        clients.remove(clientId);
    }

    @Override
    public void disableDevice(String clientId) {
        failIfRequested("disable-device");
        clients.computeIfPresent(
                clientId, (id, c) -> new Client(c.clientId(), c.tenant(), c.device(), c.secret(), false));
    }

    /** Makes every call of {@code operation} fail as if the provider were down, until {@link #reset()}. */
    public void failOn(String operation) {
        failing.add(operation);
    }

    /** The next created account gets this subject (to provoke a clash with an existing user). */
    public void nextSubject(UserId subject) {
        this.nextSubject = subject;
    }

    public @Nullable Account account(UserId subject) {
        return accounts.get(subject);
    }

    public @Nullable Client client(String clientId) {
        return clients.get(clientId);
    }

    public int clientCount() {
        return clients.size();
    }

    public void reset() {
        failing.clear();
        nextSubject = null;
    }

    private void failIfRequested(String operation) {
        if (failing.contains(operation)) {
            throw new IntegrationException(
                    IamErrorCode.IDENTITY_PROVIDER_UNAVAILABLE,
                    Map.of("operation", operation),
                    new IOException("connection refused (simulated)"));
        }
    }
}
