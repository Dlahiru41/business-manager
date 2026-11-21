package com.bmp.iam.adapter.out.idp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.bmp.iam.domain.port.IdentityProviderGateway.DeviceCredential;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewDeviceClient;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewIdentity;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.error.IntegrationException;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class KeycloakIdentityProviderTest {

    private static final String BASE = "http://keycloak.test";
    private static final String ADMIN = BASE + "/admin/realms/bmp";
    private static final String TOKEN_URL = BASE + "/realms/bmp/protocol/openid-connect/token";
    private static final TenantId TENANT = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000a01"));
    private static final UserId SUBJECT = UserId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000b09"));

    private MockRestServiceServer server;
    private KeycloakIdentityProvider gateway;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        breaker = CircuitBreaker.ofDefaults("keycloak-test");
        RetryTemplate retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(2)
                .delay(Duration.ofMillis(1))
                .includes(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        gateway = new KeycloakIdentityProvider(
                builder.build(),
                new KeycloakIdentityProvider.Settings(
                        URI.create(BASE), "bmp", "bmp-admin", "s3cr3t", "bmp-api", Duration.ofHours(24)),
                breaker,
                retry,
                new SecureRandom(),
                Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC));
    }

    private void expectToken() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content()
                        .formDataContains(java.util.Map.of(
                                "grant_type",
                                "client_credentials",
                                "client_id",
                                "bmp-admin",
                                "client_secret",
                                "s3cr3t")))
                .andRespond(withSuccess(
                        "{\"access_token\": \"admin-token\", \"expires_in\": 300}", MediaType.APPLICATION_JSON));
    }

    private static String code(Throwable e) {
        return ((BmpException) e).errorCode().code();
    }

    @Test
    void createsAUserWithTenantAndRolesAndSendsTheInvitation() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer admin-token"))
                .andExpect(jsonPath("$.username").value("nimal"))
                .andExpect(jsonPath("$.attributes.tenant_id[0]").value(TENANT.toString()))
                .andExpect(jsonPath("$.attributes.bmp_roles[0]").value("CASHIER"))
                .andExpect(jsonPath("$.requiredActions[0]").value("UPDATE_PASSWORD"))
                .andExpect(jsonPath("$.credentials").doesNotExist())
                .andRespond(withCreatedEntity(URI.create(ADMIN + "/users/" + SUBJECT)));
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT + "/execute-actions-email?lifespan=86400"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        UserId created =
                gateway.createUser(new NewIdentity("nimal", "nimal@example.lk", "Nimal", TENANT, List.of("CASHIER")));

        assertThat(created).isEqualTo(SUBJECT);
        server.verify();
    }

    @Test
    void aFailedInvitationDoesNotFailTheCreation() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users"))
                .andRespond(withCreatedEntity(URI.create(ADMIN + "/users/" + SUBJECT)));
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT + "/execute-actions-email?lifespan=86400"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(gateway.createUser(new NewIdentity("nimal", "n@example.lk", "N", TENANT, List.of())))
                .isEqualTo(SUBJECT);
    }

    @Test
    void reportsATakenUsername() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> gateway.createUser(new NewIdentity("taken", null, "T", TENANT, List.of())))
                .satisfies(e -> assertThat(code(e)).isEqualTo("USERNAME_TAKEN"));
    }

    @Test
    void neverRetriesCreationAndReportsUnavailability() {
        expectToken();
        server.expect(once(), requestTo(ADMIN + "/users")).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.createUser(new NewIdentity("x", null, "X", TENANT, List.of())))
                .isInstanceOf(IntegrationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE"));
        server.verify();
    }

    @Test
    void retriesIdempotentCallsBeforeGivingUp() {
        expectToken();
        server.expect(times(3), requestTo(ADMIN + "/users/" + SUBJECT)).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.setRoles(SUBJECT, List.of("MANAGER")))
                .isInstanceOf(IntegrationException.class);
        server.verify();
    }

    @Test
    void replacesRolesKeepingOtherAttributes() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\": \"" + SUBJECT + "\", \"enabled\": true, \"attributes\": {\"tenant_id\": [\"" + TENANT
                                + "\"], \"bmp_roles\": [\"CASHIER\"]}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.attributes.tenant_id[0]").value(TENANT.toString()))
                .andExpect(jsonPath("$.attributes.bmp_roles[0]").value("MANAGER"))
                .andExpect(jsonPath("$.attributes.bmp_roles[1]").value("CASHIER"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        gateway.setRoles(SUBJECT, List.of("MANAGER", "CASHIER"));
        server.verify();
    }

    @Test
    void disablingEndsTheSessionsAndRefreshesARejectedToken() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT)).andRespond(withUnauthorizedRequest());
        expectToken();
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT))
                .andRespond(
                        withSuccess("{\"id\": \"" + SUBJECT + "\", \"enabled\": true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT + "/logout"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        gateway.setEnabled(SUBJECT, false);
        server.verify();
    }

    @Test
    void deletingAMissingAccountOrClientIsNotAnError() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT)).andRespond(withResourceNotFound());
        server.expect(requestTo(ADMIN + "/clients?clientId=device-x"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        gateway.deleteUser(SUBJECT);
        gateway.deleteDevice("device-x");
        server.verify();
    }

    @Test
    void registersADeviceClientWithHardcodedClaimsAndAFreshSecret() {
        DeviceId device = DeviceId.of(UUID.randomUUID());
        expectToken();
        server.expect(requestTo(ADMIN + "/clients"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.clientId").value("device-" + device))
                .andExpect(jsonPath("$.serviceAccountsEnabled").value(true))
                .andExpect(jsonPath("$.publicClient").value(false))
                .andExpect(
                        jsonPath("$.protocolMappers[0].config['claim.value']").value(TENANT.toString()))
                .andExpect(
                        jsonPath("$.protocolMappers[1].config['claim.value']").value(device.toString()))
                .andExpect(
                        jsonPath("$.protocolMappers[2].config['claim.value']").value("[\"DEVICE\"]"))
                .andExpect(jsonPath("$.protocolMappers[3].config['included.client.audience']")
                        .value("bmp-api"))
                .andRespond(withCreatedEntity(URI.create(ADMIN + "/clients/" + UUID.randomUUID())));

        DeviceCredential credential =
                gateway.registerDevice(new NewDeviceClient(TENANT, device, "device-" + device, "Till 1"));

        assertThat(credential.clientId()).isEqualTo("device-" + device);
        assertThat(credential.clientSecret()).hasSize(43).doesNotContain("=");
        assertThat(credential.toString()).doesNotContain(credential.clientSecret());
        server.verify();
    }

    @Test
    void disablesADeviceClientAndRotatesItsSecret() {
        String internal = UUID.randomUUID().toString();
        expectToken();
        server.expect(requestTo(ADMIN + "/clients?clientId=device-1"))
                .andRespond(withSuccess("[{\"id\": \"" + internal + "\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/clients/" + internal))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\": \"" + internal + "\", \"clientId\": \"device-1\", \"enabled\": true}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/clients/" + internal))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(ADMIN + "/clients/" + internal + "/client-secret"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/clients?clientId=device-2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        gateway.disableDevice("device-1");
        gateway.disableDevice("device-2");
        server.verify();
    }

    @Test
    void rejectsUnexpectedClientErrorsAndAnOpenCircuit() {
        expectToken();
        server.expect(requestTo(ADMIN + "/users/" + SUBJECT)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> gateway.setEnabled(SUBJECT, true))
                .isInstanceOf(IntegrationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE"));

        breaker.transitionToOpenState();
        assertThatThrownBy(() -> gateway.deleteUser(SUBJECT))
                .isInstanceOf(IntegrationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void masksTheClientSecretInSettings() {
        assertThat(new KeycloakIdentityProvider.Settings(
                                URI.create(BASE), "bmp", "bmp-admin", "s3cr3t", "bmp-api", Duration.ofHours(1))
                        .toString())
                .doesNotContain("s3cr3t");
    }
}
