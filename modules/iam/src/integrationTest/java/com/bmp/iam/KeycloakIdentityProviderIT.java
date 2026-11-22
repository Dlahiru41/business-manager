package com.bmp.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.iam.adapter.out.idp.KeycloakIdentityProvider;
import com.bmp.iam.domain.port.IdentityProviderGateway.DeviceCredential;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewDeviceClient;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewIdentity;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.web.security.BmpAuthentication;
import com.bmp.web.security.BmpJwtAuthenticationConverter;
import com.bmp.web.security.BmpPrincipal;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Keycloak adapter against a real Keycloak with the local realm (deploy/local/keycloak/realm-bmp.json): accounts
 * and device clients it creates yield tokens with exactly the claims the API expects, and disabling them stops new
 * tokens.
 */
class KeycloakIdentityProviderIT {

    private static final String ADMIN_SECRET = "it-admin-secret";
    private static final String DEV_PASSWORD = "It-Passw0rd!";
    private static final TenantId PILOT = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000a01"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8.2")
            .withRealmImportFile("/realm-bmp.json")
            .withEnv("BMP_KEYCLOAK_ADMIN_CLIENT_SECRET", ADMIN_SECRET)
            .withEnv("BMP_DEV_USER_PASSWORD", DEV_PASSWORD)
            .withStartupTimeout(Duration.ofMinutes(3));

    private static KeycloakIdentityProvider gateway;
    private static RestClient http;

    @BeforeAll
    static void start() {
        KEYCLOAK.start();
        http = RestClient.builder().baseUrl(KEYCLOAK.getAuthServerUrl()).build();
        gateway = new KeycloakIdentityProvider(
                RestClient.builder().baseUrl(KEYCLOAK.getAuthServerUrl()).build(),
                new KeycloakIdentityProvider.Settings(
                        URI.create(KEYCLOAK.getAuthServerUrl()),
                        "bmp",
                        "bmp-admin",
                        ADMIN_SECRET,
                        "bmp-api",
                        Duration.ofHours(1)),
                CircuitBreaker.ofDefaults("keycloak-it"),
                new RetryTemplate(RetryPolicy.withMaxRetries(1)),
                new SecureRandom(),
                Clock.systemUTC());
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop();
    }

    private static JsonNode token(MultiValueMap<String, String> form) {
        JsonNode response = http.post()
                .uri("/realms/bmp/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String jwt = response.path("access_token").asString();
        String payload = jwt.substring(jwt.indexOf('.') + 1, jwt.lastIndexOf('.'));
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private static JsonNode passwordToken(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "bmp-dev-cli");
        form.add("username", username);
        form.add("password", password);
        return token(form);
    }

    private static JsonNode clientToken(DeviceCredential credential) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", credential.clientId());
        form.add("client_secret", credential.clientSecret());
        return token(form);
    }

    /** Test-only: gives the new account a password and clears its required actions, as the user would. */
    private static void setPassword(UserId subject, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", KEYCLOAK.getAdminUsername());
        form.add("password", KEYCLOAK.getAdminPassword());
        String admin = http.post()
                .uri("/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class)
                .path("access_token")
                .asString();
        http.put()
                .uri("/admin/realms/bmp/users/{id}/reset-password", subject.value())
                .headers(h -> h.setBearerAuth(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", false))
                .retrieve()
                .toBodilessEntity();
        JsonNode user = http.get()
                .uri("/admin/realms/bmp/users/{id}", subject.value())
                .headers(h -> h.setBearerAuth(admin))
                .retrieve()
                .body(JsonNode.class);
        ((tools.jackson.databind.node.ObjectNode) user).putArray("requiredActions");
        http.put()
                .uri("/admin/realms/bmp/users/{id}", subject.value())
                .headers(h -> h.setBearerAuth(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity();
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return array.isMissingNode() ? List.of() : List.of(array.asString());
        }
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    /** What the API makes of the claims: the same converter as production. */
    private static BmpPrincipal principal(JsonNode claims) {
        Map<String, Object> map = JSON.convertValue(claims, new TypeReference<Map<String, Object>>() {});
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claims(c -> c.putAll(map))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return ((BmpAuthentication) new BmpJwtAuthenticationConverter(List.of("bmp-dev-cli")).convert(jwt))
                .bmpPrincipal();
    }

    @Test
    void theSeededPilotOwnerGetsTenantAndRoleClaims() {
        JsonNode claims = passwordToken("pilot-owner", DEV_PASSWORD);

        assertThat(claims.path("tenant_id").asString()).isEqualTo(PILOT.toString());
        assertThat(strings(claims.path("roles"))).containsExactly("OWNER");
        assertThat(strings(claims.path("aud"))).contains("bmp-api");
        assertThat(principal(claims).tenantRoles()).containsExactly("OWNER");
    }

    @Test
    void accountsCarryTheirTenantAndRolesAndStopWhenDisabled() {
        String username = "it-" + UUID.randomUUID().toString().substring(0, 8);
        UserId subject = gateway.createUser(new NewIdentity(username, null, "IT user", PILOT, List.of("CASHIER")));
        setPassword(subject, DEV_PASSWORD);
        assertThatThrownBy(() -> passwordToken(username, DEV_PASSWORD))
                .as("created disabled until the profile is stored")
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(gateway.findUser(username)).hasValueSatisfying(found -> {
            assertThat(found.subject()).isEqualTo(subject);
            assertThat(found.tenant()).isEqualTo(PILOT.toString());
            assertThat(found.enabled()).isFalse();
        });
        assertThat(gateway.findUser("nobody-" + username)).isEmpty();
        gateway.activateUser(subject, false);

        JsonNode first = passwordToken(username, DEV_PASSWORD);
        assertThat(first.path("sub").asString()).isEqualTo(subject.toString());
        assertThat(first.path("tenant_id").asString()).isEqualTo(PILOT.toString());
        assertThat(strings(first.path("roles"))).containsExactly("CASHIER");
        BmpPrincipal principal = principal(first);
        assertThat(principal.userId()).isEqualTo(subject);
        assertThat(principal.tenantId()).isEqualTo(PILOT);

        gateway.setRoles(subject, List.of("MANAGER", "SUPERVISOR"));
        assertThat(strings(passwordToken(username, DEV_PASSWORD).path("roles")))
                .containsExactlyInAnyOrder("MANAGER", "SUPERVISOR");
        assertThat(passwordToken(username, DEV_PASSWORD).path("tenant_id").asString())
                .as("changing roles keeps the tenant attribute")
                .isEqualTo(PILOT.toString());

        assertThatThrownBy(() -> gateway.createUser(new NewIdentity(username, null, "Again", PILOT, List.of())))
                .isInstanceOfSatisfying(
                        BmpException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("USERNAME_TAKEN"));

        gateway.setEnabled(subject, false);
        assertThatThrownBy(() -> passwordToken(username, DEV_PASSWORD)).isInstanceOf(HttpClientErrorException.class);
        gateway.setEnabled(subject, true);
        assertThat(passwordToken(username, DEV_PASSWORD).path("sub").asString()).isEqualTo(subject.toString());

        gateway.deleteUser(subject);
        gateway.deleteUser(subject);
        assertThatThrownBy(() -> passwordToken(username, DEV_PASSWORD)).isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void deviceClientsGetDeviceTokensAndStopWhenDisabled() {
        DeviceId device = DeviceId.of(UUID.randomUUID());
        DeviceCredential credential =
                gateway.registerDevice(new NewDeviceClient(PILOT, device, "device-" + device, "Till IT"));

        JsonNode claims = clientToken(credential);
        assertThat(claims.path("tenant_id").asString()).isEqualTo(PILOT.toString());
        assertThat(claims.path("device_id").asString()).isEqualTo(device.toString());
        assertThat(strings(claims.path("roles"))).containsExactly("DEVICE");
        assertThat(strings(claims.path("aud"))).contains("bmp-api");
        BmpPrincipal principal = principal(claims);
        assertThat(principal.deviceId()).isEqualTo(device);
        assertThat(principal.tenantRoles()).containsExactly("DEVICE");

        gateway.disableDevice(credential.clientId());
        assertThatThrownBy(() -> clientToken(credential)).isInstanceOf(HttpClientErrorException.class);
        gateway.disableDevice(credential.clientId());

        gateway.deleteDevice(credential.clientId());
        gateway.deleteDevice(credential.clientId());
    }
}
