package com.bmp.iam;

import static com.bmp.iam.IamApi.CONTRACT;
import static com.bmp.iam.IamApi.deviceToken;
import static com.bmp.iam.IamApi.json;
import static com.bmp.iam.IamApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.iam.api.events.DeviceRegistered;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Device registration with a one-time secret, DeviceRegistered, and revocation (IAM-03). */
@SpringBootTest(classes = IamTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class DeviceApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    FakeIdentityProvider identityProvider;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    private IamApi api;
    private TenantId tenant;
    private String owner;
    private UUID location;
    private final UUID register = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        api = new IamApi(mvc, jdbc, transactionManager);
        tenant = api.createTenant("Devices " + UUID.randomUUID());
        owner = token(tenant, SystemRole.OWNER);
        location = api.mainLocation(tenant);
    }

    @AfterEach
    void resetIdentityProvider() {
        identityProvider.reset();
    }

    private MvcTestResult register(String key) {
        return api.post(
                "/api/v1/devices",
                owner,
                "{\"name\": \"Till 1\", \"locationId\": \"" + location + "\", \"registerId\": \"" + register + "\"}",
                key);
    }

    @Test
    void registersADeviceReturningItsSecretOnceAndPublishesTheEvent() {
        String key = UUID.randomUUID().toString();

        MvcTestResult registered = register(key);
        MvcTestResult replay = register(key);

        assertThat(registered).hasStatus(201);
        CONTRACT.assertConforms(registered);
        UUID deviceId =
                UUID.fromString(json(registered).path("device").path("id").asString());
        String clientId = json(registered).path("device").path("clientId").asString();
        assertThat(clientId).isEqualTo("device-" + deviceId);
        FakeIdentityProvider.Client client = identityProvider.client(clientId);
        assertThat(client).isNotNull();
        assertThat(client.tenant()).isEqualTo(tenant.toString());
        assertThat(json(registered).path("clientSecret").asString()).isEqualTo(client.secret());

        assertThat(replay).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        CONTRACT.assertConforms(replay);
        assertThat(json(replay).has("clientSecret"))
                .as("never stored, never replayed")
                .isFalse();
        assertThat(json(replay).path("device").path("id").asString()).isEqualTo(deviceId.toString());
        assertThat(api.<Long>sql(
                        tenant,
                        db -> db.sql(
                                        "SELECT count(*) FROM platform.idempotency_record WHERE response_body LIKE :secret")
                                .param("secret", "%" + client.secret() + "%")
                                .query(Long.class)
                                .single()))
                .isZero();

        var published = events.stream(DeviceRegistered.class)
                .filter(e -> e.deviceId().value().equals(deviceId))
                .toList();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().locationId().value()).isEqualTo(location);
        EventContract.assertConforms(json.writeValueAsString(published.getFirst()), "DeviceRegistered");
    }

    @Test
    void rejectsAnUnknownLocationAndAnUnavailableIdentityProvider() {
        int before = identityProvider.clientCount();
        MvcTestResult badLocation = api.post(
                "/api/v1/devices", owner, "{\"name\": \"Till\", \"locationId\": \"" + UUID.randomUUID() + "\"}");
        identityProvider.failOn("register-device");
        MvcTestResult down = register(UUID.randomUUID().toString());

        assertThatProblem(badLocation).hasStatus(422).hasCode("INVALID_LOCATION");
        assertThatProblem(down).hasStatus(503).hasCode("IDENTITY_PROVIDER_UNAVAILABLE");
        CONTRACT.assertConforms(down);
        assertThat(identityProvider.clientCount()).isEqualTo(before);
    }

    @Test
    void aRevokedDeviceIsRejectedAtOnce() {
        MvcTestResult registered = register(UUID.randomUUID().toString());
        UUID deviceId =
                UUID.fromString(json(registered).path("device").path("id").asString());
        String till = deviceToken(tenant, DeviceId.of(deviceId));
        assertThat(api.get("/api/v1/me", till)).hasStatus(200);

        MvcTestResult revoked =
                api.post("/api/v1/devices/" + deviceId + "/revoke", owner, "{\"reason\": \"Till stolen\"}");
        MvcTestResult afterRevocation = api.get("/api/v1/me", till);
        MvcTestResult again = api.post("/api/v1/devices/" + deviceId + "/revoke", owner, "{\"reason\": \"Again\"}");

        assertThat(revoked).hasStatus(200).hasHeader("ETag", "W/\"1\"");
        CONTRACT.assertConforms(revoked);
        assertThat(json(revoked).path("status").asString()).isEqualTo("REVOKED");
        assertThat(identityProvider.client("device-" + deviceId).enabled()).isFalse();
        assertThatProblem(afterRevocation).hasStatus(403).hasCode("DEVICE_REVOKED");
        assertThatProblem(again).hasStatus(409).hasCode("DEVICE_ALREADY_REVOKED");
        CONTRACT.assertConforms(again);
    }

    @Test
    void anUnknownDeviceIsRejectedAndCannotBeRevoked() {
        String unknownTill = deviceToken(tenant, DeviceId.of(UUID.randomUUID()));

        MvcTestResult call = api.get("/api/v1/me", unknownTill);
        MvcTestResult revoke =
                api.post("/api/v1/devices/" + UUID.randomUUID() + "/revoke", owner, "{\"reason\": \"x\"}");

        assertThatProblem(call).hasStatus(403).hasCode("DEVICE_REVOKED");
        assertThatProblem(revoke).hasStatus(404).hasCode("DEVICE_NOT_FOUND");
    }

    @Test
    void keepsTheDeviceActiveWhenTheIdentityProviderCannotDisableIt() {
        MvcTestResult registered = register(UUID.randomUUID().toString());
        UUID deviceId =
                UUID.fromString(json(registered).path("device").path("id").asString());
        identityProvider.failOn("disable-device");

        MvcTestResult failed = api.post("/api/v1/devices/" + deviceId + "/revoke", owner, "{\"reason\": \"Lost\"}");

        assertThatProblem(failed).hasStatus(503).hasCode("IDENTITY_PROVIDER_UNAVAILABLE");
        assertThat(api.<String>sql(
                        tenant,
                        db -> db.sql("SELECT status FROM iam.device WHERE id = :id")
                                .param("id", deviceId)
                                .query(String.class)
                                .single()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void anotherTenantCannotRevokeTheDevice() {
        MvcTestResult registered = register(UUID.randomUUID().toString());
        UUID deviceId =
                UUID.fromString(json(registered).path("device").path("id").asString());
        TenantId other = api.createTenant("Devices other " + UUID.randomUUID());

        MvcTestResult revoke = api.post(
                "/api/v1/devices/" + deviceId + "/revoke", token(other, SystemRole.OWNER), "{\"reason\": \"x\"}");
        MvcTestResult cashier = api.post(
                "/api/v1/devices/" + deviceId + "/revoke", token(tenant, SystemRole.CASHIER), "{\"reason\": \"x\"}");

        assertThatProblem(revoke).hasStatus(404).hasCode("DEVICE_NOT_FOUND");
        assertThatProblem(cashier).hasStatus(403).hasCode("ACCESS_DENIED");
    }
}
