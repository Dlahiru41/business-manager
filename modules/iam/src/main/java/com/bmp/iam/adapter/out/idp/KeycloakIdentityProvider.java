package com.bmp.iam.adapter.out.idp;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.IntegrationException;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.Sensitive;
import com.bmp.shared.security.SystemRole;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link IdentityProviderGateway} on the Keycloak Admin REST API, authenticated as the {@code bmp-admin} service
 * account (client credentials, token cached until shortly before it expires).
 *
 * <ul>
 *   <li>Tenant roles travel as the admin-only user attribute {@code bmp_roles}, mapped to the {@code roles} claim; the
 *       tenant as {@code tenant_id} (ADR-014, ADR-016). Realm roles are used only for {@code platform_admin}.
 *   <li>Device clients are confidential clients {@code device-<id>} whose tokens carry hardcoded {@code tenant_id},
 *       {@code device_id} and {@code roles: ["DEVICE"]} claims and the {@code bmp-api} audience.
 *   <li>Timeouts come from the HTTP client; idempotent calls are retried on I/O errors and 5xx; every call goes
 *       through a circuit breaker. Unavailability surfaces as {@code IDENTITY_PROVIDER_UNAVAILABLE}.
 * </ul>
 */
public class KeycloakIdentityProvider implements IdentityProviderGateway {

    static final String TENANT_ATTRIBUTE = "tenant_id";
    static final String ROLES_ATTRIBUTE = "bmp_roles";
    static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvider.class);
    private static final int SECRET_BYTES = 32;
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(30);
    private static final long DEFAULT_TOKEN_SECONDS = 60;

    private final RestClient http;
    private final Settings settings;
    private final CircuitBreaker breaker;
    private final RetryTemplate retry;
    private final SecureRandom random;
    private final Clock clock;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile @Nullable AccessToken token;

    /**
     * Connection settings.
     *
     * @param baseUrl Keycloak base URL, e.g. {@code http://localhost:8180}
     * @param clientId the service account client, {@code bmp-admin}
     * @param apiAudience audience the API expects in tokens, {@code bmp-api}
     * @param inviteLifespan validity of the "set your password" e-mail link
     */
    @Sensitive
    public record Settings(
            URI baseUrl,
            String realm,
            String clientId,
            String clientSecret,
            String apiAudience,
            Duration inviteLifespan) {

        public Settings {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(realm, "realm");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(clientSecret, "clientSecret");
            Objects.requireNonNull(apiAudience, "apiAudience");
            Objects.requireNonNull(inviteLifespan, "inviteLifespan");
        }

        @Override
        public String toString() {
            return "Settings[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId
                    + ", clientSecret=***]";
        }
    }

    private record AccessToken(String value, Instant refreshAfter) {}

    public KeycloakIdentityProvider(
            RestClient http,
            Settings settings,
            CircuitBreaker breaker,
            RetryTemplate retry,
            SecureRandom random,
            Clock clock) {
        this.http = http;
        this.settings = settings;
        this.breaker = breaker;
        this.retry = retry;
        this.random = random;
        this.clock = clock;
    }

    @Override
    public UserId createUser(NewIdentity identity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", identity.username());
        body.put("enabled", true);
        String email = identity.email();
        if (email != null) {
            body.put("email", email);
            body.put("emailVerified", false);
        }
        body.put(
                "attributes",
                Map.of(TENANT_ATTRIBUTE, List.of(identity.tenant().toString()), ROLES_ATTRIBUTE, identity.roleCodes()));
        body.put("requiredActions", List.of(UPDATE_PASSWORD));
        URI location;
        try {
            location = call(
                    "create-user",
                    false,
                    () -> http.post()
                            .uri("/admin/realms/{realm}/users", settings.realm())
                            .headers(this::authorize)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .toBodilessEntity()
                            .getHeaders()
                            .getLocation());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new BusinessRuleViolation(IamErrorCode.USERNAME_TAKEN, Map.of());
            }
            throw rejected("create-user", e);
        }
        UserId subject = UserId.of(lastSegment(location, "create-user"));
        if (email != null) {
            invite(subject);
        }
        return subject;
    }

    @Override
    public void deleteUser(UserId subject) {
        try {
            call(
                    "delete-user",
                    true,
                    () -> http.delete()
                            .uri("/admin/realms/{realm}/users/{id}", settings.realm(), subject.value())
                            .headers(this::authorize)
                            .retrieve()
                            .toBodilessEntity());
        } catch (HttpClientErrorException e) {
            if (!e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw rejected("delete-user", e);
            }
        }
    }

    @Override
    public void setRoles(UserId subject, List<String> roleCodes) {
        updateUser("set-roles", subject, user -> {
            JsonNode existing = user.get("attributes");
            ObjectNode attributes = existing instanceof ObjectNode object ? object : user.putObject("attributes");
            ArrayNode roles = attributes.putArray(ROLES_ATTRIBUTE);
            roleCodes.forEach(roles::add);
        });
    }

    @Override
    public void setEnabled(UserId subject, boolean enabled) {
        updateUser("set-enabled", subject, user -> user.put("enabled", enabled));
        if (!enabled) {
            try {
                call(
                        "logout-user",
                        true,
                        () -> http.post()
                                .uri("/admin/realms/{realm}/users/{id}/logout", settings.realm(), subject.value())
                                .headers(this::authorize)
                                .retrieve()
                                .toBodilessEntity());
            } catch (HttpClientErrorException e) {
                throw rejected("logout-user", e);
            }
        }
    }

    @Override
    public DeviceCredential registerDevice(NewDeviceClient device) {
        String secret = newSecret();
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", device.clientId());
        client.put("name", device.name());
        client.put("description", "BMP device " + device.device());
        client.put("enabled", true);
        client.put("protocol", "openid-connect");
        client.put("publicClient", false);
        client.put("clientAuthenticatorType", "client-secret");
        client.put("secret", secret);
        client.put("serviceAccountsEnabled", true);
        client.put("standardFlowEnabled", false);
        client.put("implicitFlowEnabled", false);
        client.put("directAccessGrantsEnabled", false);
        client.put("fullScopeAllowed", false);
        client.put(
                "protocolMappers",
                List.of(
                        hardcodedClaim("tenant_id", device.tenant().toString(), "String"),
                        hardcodedClaim("device_id", device.device().toString(), "String"),
                        hardcodedClaim("roles", "[\"" + SystemRole.DEVICE.code() + "\"]", "JSON"),
                        audience()));
        try {
            call(
                    "register-device",
                    false,
                    () -> http.post()
                            .uri("/admin/realms/{realm}/clients", settings.realm())
                            .headers(this::authorize)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(client)
                            .retrieve()
                            .toBodilessEntity());
        } catch (HttpClientErrorException e) {
            throw rejected("register-device", e);
        }
        return new DeviceCredential(device.clientId(), secret);
    }

    @Override
    public void deleteDevice(String clientId) {
        Optional<String> id = findClient(clientId);
        if (id.isEmpty()) {
            return;
        }
        try {
            call(
                    "delete-device",
                    true,
                    () -> http.delete()
                            .uri("/admin/realms/{realm}/clients/{id}", settings.realm(), id.get())
                            .headers(this::authorize)
                            .retrieve()
                            .toBodilessEntity());
        } catch (HttpClientErrorException e) {
            if (!e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw rejected("delete-device", e);
            }
        }
    }

    @Override
    public void disableDevice(String clientId) {
        Optional<String> found = findClient(clientId);
        if (found.isEmpty()) {
            log.atWarn()
                    .addKeyValue("clientId", clientId)
                    .log("Device client not found in the identity provider, nothing to disable clientId={}", clientId);
            return;
        }
        String id = found.get();
        try {
            ObjectNode client = call(
                    "read-device",
                    true,
                    () -> http.get()
                            .uri("/admin/realms/{realm}/clients/{id}", settings.realm(), id)
                            .headers(this::authorize)
                            .retrieve()
                            .body(ObjectNode.class));
            Objects.requireNonNull(client, "client").put("enabled", false);
            call(
                    "disable-device",
                    true,
                    () -> http.put()
                            .uri("/admin/realms/{realm}/clients/{id}", settings.realm(), id)
                            .headers(this::authorize)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(client)
                            .retrieve()
                            .toBodilessEntity());
            // A new secret makes the old one useless even if the client were enabled again by mistake.
            call(
                    "rotate-device-secret",
                    true,
                    () -> http.post()
                            .uri("/admin/realms/{realm}/clients/{id}/client-secret", settings.realm(), id)
                            .headers(this::authorize)
                            .retrieve()
                            .toBodilessEntity());
        } catch (HttpClientErrorException e) {
            throw rejected("disable-device", e);
        }
    }

    private void updateUser(String operation, UserId subject, Consumer<ObjectNode> change) {
        try {
            ObjectNode user = call(
                    operation,
                    true,
                    () -> http.get()
                            .uri("/admin/realms/{realm}/users/{id}", settings.realm(), subject.value())
                            .headers(this::authorize)
                            .retrieve()
                            .body(ObjectNode.class));
            ObjectNode representation = Objects.requireNonNull(user, "user");
            change.accept(representation);
            call(
                    operation,
                    true,
                    () -> http.put()
                            .uri("/admin/realms/{realm}/users/{id}", settings.realm(), subject.value())
                            .headers(this::authorize)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(representation)
                            .retrieve()
                            .toBodilessEntity());
        } catch (HttpClientErrorException e) {
            throw rejected(operation, e);
        }
    }

    /** Sends the "set your password" e-mail. Best effort: the account exists either way and can be re-invited. */
    private void invite(UserId subject) {
        try {
            call(
                    "invite-user",
                    true,
                    () -> http.put()
                            .uri(
                                    "/admin/realms/{realm}/users/{id}/execute-actions-email?lifespan={lifespan}",
                                    settings.realm(),
                                    subject.value(),
                                    settings.inviteLifespan().toSeconds())
                            .headers(this::authorize)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(List.of(UPDATE_PASSWORD))
                            .retrieve()
                            .toBodilessEntity());
        } catch (RuntimeException e) {
            log.atWarn()
                    .addKeyValue("invitedUserId", subject.toString())
                    .setCause(e)
                    .log("Invitation e-mail could not be sent userId={}", subject);
        }
    }

    private Optional<String> findClient(String clientId) {
        try {
            JsonNode found = call(
                    "find-device",
                    true,
                    () -> http.get()
                            .uri("/admin/realms/{realm}/clients?clientId={clientId}", settings.realm(), clientId)
                            .headers(this::authorize)
                            .retrieve()
                            .body(JsonNode.class));
            if (found == null || !found.isArray() || found.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(found.get(0).path("id").asString());
        } catch (HttpClientErrorException e) {
            throw rejected("find-device", e);
        }
    }

    /**
     * Runs one Admin API call: refreshes the token once on 401, records I/O errors and 5xx in the circuit breaker,
     * retries idempotent calls, and turns unavailability into {@code IDENTITY_PROVIDER_UNAVAILABLE}. Other 4xx
     * responses are left to the caller.
     */
    private <T> T call(String operation, boolean idempotent, Supplier<T> request) {
        Supplier<T> authenticated = () -> {
            try {
                return request.get();
            } catch (HttpClientErrorException.Unauthorized e) {
                token = null;
                return request.get();
            }
        };
        Supplier<T> guarded = () -> breaker.executeSupplier(authenticated);
        try {
            return idempotent ? retry.invoke(guarded) : guarded.get();
        } catch (CallNotPermittedException | ResourceAccessException | HttpServerErrorException e) {
            throw new IntegrationException(
                    IamErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, Map.of("operation", operation), e);
        }
    }

    private IntegrationException rejected(String operation, HttpClientErrorException e) {
        return new IntegrationException(
                IamErrorCode.IDENTITY_PROVIDER_UNAVAILABLE,
                Map.of("operation", operation, "status", e.getStatusCode().value()),
                e);
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(accessToken());
    }

    private String accessToken() {
        AccessToken current = token;
        if (current != null && clock.instant().isBefore(current.refreshAfter())) {
            return current.value();
        }
        tokenLock.lock();
        try {
            current = token;
            if (current != null && clock.instant().isBefore(current.refreshAfter())) {
                return current.value();
            }
            AccessToken fresh = fetchToken();
            token = fresh;
            return fresh.value();
        } finally {
            tokenLock.unlock();
        }
    }

    private AccessToken fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        JsonNode body = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", settings.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.hasNonNull("access_token")) {
            throw new IntegrationException(
                    IamErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, Map.of("operation", "token"), null);
        }
        long seconds = body.path("expires_in").asLong(DEFAULT_TOKEN_SECONDS);
        return new AccessToken(
                body.path("access_token").asString(),
                clock.instant().plusSeconds(seconds).minus(TOKEN_EXPIRY_MARGIN));
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Map<String, Object> hardcodedClaim(String claim, String value, String jsonType) {
        return Map.of(
                "name",
                claim,
                "protocol",
                "openid-connect",
                "protocolMapper",
                "oidc-hardcoded-claim-mapper",
                "config",
                Map.of(
                        "claim.name", claim,
                        "claim.value", value,
                        "jsonType.label", jsonType,
                        "access.token.claim", "true",
                        "id.token.claim", "false",
                        "userinfo.token.claim", "false",
                        "introspection.token.claim", "true"));
    }

    private Map<String, Object> audience() {
        return Map.of(
                "name",
                "bmp-api-audience",
                "protocol",
                "openid-connect",
                "protocolMapper",
                "oidc-audience-mapper",
                "config",
                Map.of(
                        "included.client.audience", settings.apiAudience(),
                        "access.token.claim", "true",
                        "id.token.claim", "false",
                        "introspection.token.claim", "true"));
    }

    private static UUID lastSegment(@Nullable URI location, String operation) {
        if (location == null) {
            throw new IntegrationException(
                    IamErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, Map.of("operation", operation), null);
        }
        String path = location.getPath();
        try {
            return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        } catch (IllegalArgumentException e) {
            throw new IntegrationException(
                    IamErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, Map.of("operation", operation), e);
        }
    }
}
