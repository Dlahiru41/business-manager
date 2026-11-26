package com.bmp.tenant;

import static com.bmp.tenant.TenantApi.CONTRACT;
import static com.bmp.tenant.TenantApi.etag;
import static com.bmp.tenant.TenantApi.json;
import static com.bmp.tenant.TenantApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.tenant.api.TenantProvisioning;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.NewTenant;
import com.bmp.tenant.api.events.LocationCreated;
import com.bmp.tenant.api.events.TenantCreated;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class TenantLifecycleApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    TenantProvisioning provisioning;

    @Autowired
    TenantQueries queries;

    @Autowired
    PlatformTransactionManager transactions;

    private TenantApi api;

    @BeforeEach
    void setUp() {
        api = new TenantApi(mvc);
    }

    @Test
    void platformAdminCreatesTenantWithMainShopAndInitialSettings() {
        MvcTestResult created = api.post(
                "/api/v1/tenants",
                JwtTestTokens.platformAdmin(),
                "{\"name\": \"Perera Stores\", \"businessType\": \"RETAIL_GROCERY\", \"currency\": \"LKR\"}");

        assertThat(created).hasStatus(201).hasHeader("ETag", "W/\"0\"");
        CONTRACT.assertConforms(created);
        TenantId tenant = TenantId.of(UUID.fromString(json(created).path("id").asString()));
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/tenants/" + tenant);
        assertThat(json(created).path("timeZone").asString()).isEqualTo("Asia/Colombo");

        MvcTestResult locations = api.get("/api/v1/locations", token(tenant, SystemRole.OWNER));
        assertThat(json(locations).path("items").size())
                .as("TEN-01: one default location")
                .isEqualTo(1);
        assertThat(json(locations).path("items").get(0).path("name").asString()).isEqualTo("Main shop");
        String mainShopId = json(locations).path("items").get(0).path("id").asString();

        MvcTestResult inventory = api.get("/api/v1/settings/inventory", token(tenant, SystemRole.OWNER));
        assertThat(json(inventory).path("values").path("defaultLocationId").asString())
                .isEqualTo(mainShopId);
        assertThat(events.stream(TenantCreated.class).filter(e -> e.tenantId().equals(tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.currency()).isEqualTo("LKR"));
        assertThat(events.stream(LocationCreated.class).filter(e -> e.tenantId().equals(tenant)))
                .hasSize(1);
    }

    @Test
    void createTenantShouldReplayForTheSameIdempotencyKey() {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\": \"Replay Stores\", \"businessType\": \"RETAIL\"}";

        String admin = JwtTestTokens.platformAdmin();

        MvcTestResult first = api.post("/api/v1/tenants", admin, body, key);
        MvcTestResult replay = api.post("/api/v1/tenants", admin, body, key);
        MvcTestResult otherAdmin = api.post("/api/v1/tenants", JwtTestTokens.platformAdmin(), body, key);

        assertThat(replay).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(json(replay).path("id")).isEqualTo(json(first).path("id"));
        assertThatProblem(otherAdmin)
                .as("a stored response is never replayed to another caller")
                .hasStatus(422)
                .hasCode("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void createTenantShouldRequirePlatformPermissionAndValidInput() {
        TenantId tenant = api.createTenant("Gate Stores");

        assertThatProblem(mvc.post().uri("/api/v1/tenants").exchange()).hasStatus(401);
        assertThatProblem(api.post(
                        "/api/v1/tenants",
                        token(tenant, SystemRole.OWNER),
                        "{\"name\": \"x\", \"businessType\": \"y\"}"))
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
        assertThatProblem(api.post("/api/v1/tenants", JwtTestTokens.platformAdmin(), "{\"businessType\": \"y\"}"))
                .hasStatus(422)
                .hasFieldError("name", "NOT_NULL");
        assertThatProblem(
                        api.post(
                                "/api/v1/tenants",
                                JwtTestTokens.platformAdmin(),
                                "{\"name\": \"x\", \"businessType\": \"y\", \"currency\": \"ABC\", \"timeZone\": \"Mars/Base\"}"))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED")
                .hasFieldError("currency", "UNKNOWN_CURRENCY")
                .hasFieldError("timeZone", "UNKNOWN_TIME_ZONE");
        assertThatProblem(api.post(
                        "/api/v1/tenants",
                        JwtTestTokens.platformAdmin(),
                        "{\"name\": \"Yen shop\", \"businessType\": \"y\", \"currency\": \"JPY\"}"))
                .as("money rounds to two decimals for now")
                .hasStatus(422)
                .hasFieldError("currency", "UNSUPPORTED_CURRENCY");
    }

    @Test
    void ownerReadsAndUpdatesTheProfileWithOptimisticLocking() {
        TenantId tenant = api.createTenant("Profile Stores");
        String owner = token(tenant, SystemRole.OWNER);

        MvcTestResult current = api.get("/api/v1/tenant", owner);
        CONTRACT.assertConforms(current);
        String body = "{\"legalName\": \"Profile Stores (Pvt) Ltd\"}";

        assertThatProblem(api.patch("/api/v1/tenant", owner, body, null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        assertThatProblem(api.patch("/api/v1/tenant", owner, body, "W/\"7\""))
                .hasStatus(412)
                .hasDetail("currentVersion", "0");
        MvcTestResult updated = api.patch("/api/v1/tenant", owner, body, etag(current));
        assertThat(updated).hasStatusOk().hasHeader("ETag", "W/\"1\"");
        CONTRACT.assertConforms(updated);
        assertThat(json(updated).path("legalName").asString()).isEqualTo("Profile Stores (Pvt) Ltd");
        assertThat(json(updated).path("name").asString()).isEqualTo("Profile Stores");
        assertThatProblem(api.get("/api/v1/tenant", token(tenant, SystemRole.CASHIER)))
                .hasStatus(403);
    }

    @Test
    void ten04SuspendedTenantIsReadOnlyUntilReactivated() {
        TenantId tenant = api.createTenant("Suspended Stores");
        String owner = token(tenant, SystemRole.OWNER);
        String admin = JwtTestTokens.platformAdmin();
        String reason = "{\"reason\": \"Subscription unpaid\"}";

        MvcTestResult suspended = api.post("/api/v1/tenants/" + tenant + "/suspend", admin, reason);
        assertThat(suspended).hasStatusOk();
        CONTRACT.assertConforms(suspended);
        assertThat(json(suspended).path("status").asString()).isEqualTo("SUSPENDED");

        assertThat(api.get("/api/v1/locations", owner)).hasStatusOk();
        assertThatProblem(api.post("/api/v1/locations", owner, "{\"name\": \"Store\", \"type\": \"STORE\"}"))
                .hasStatus(403)
                .hasCode("TENANT_SUSPENDED");
        assertThatProblem(api.post("/api/v1/tenants/" + tenant + "/suspend", admin, reason))
                .hasStatus(409)
                .hasCode("INVALID_TENANT_STATUS_TRANSITION");

        assertThat(api.post("/api/v1/tenants/" + tenant + "/reactivate", admin, reason))
                .hasStatusOk();
        assertThat(api.post("/api/v1/locations", owner, "{\"name\": \"Store\", \"type\": \"STORE\"}"))
                .hasStatus(201);
        assertThatProblem(api.post("/api/v1/tenants/" + UUID.randomUUID() + "/suspend", admin, reason))
                .hasStatus(404)
                .hasCode("TENANT_NOT_FOUND");
    }

    @Test
    void provisioningShouldBeIdempotentAndQueryableThroughTheApi() {
        TenantId id = TenantId.of(UUID.randomUUID());
        NewTenant spec = new NewTenant("Seeded Shop", null, "RETAIL", "LKR", "Asia/Colombo", 4, "STANDARD", null);

        var first = provisioning.provision(id, spec);
        var second = provisioning.provision(id, spec);

        assertThat(second).isEqualTo(first);
        assertThat(first.businessDayCutoffHour()).isEqualTo(4);
        assertThat(queries.findTenant(id)).contains(first);
        assertThat(queries.findTenant(TenantId.of(UUID.randomUUID()))).isEmpty();
        assertThat(TenantContext.callWith(id, () -> queries.currentTenant()))
                .as("the tenant of the context, read in the caller's transaction")
                .contains(first);
    }

    @Test
    void settingsAreReadOncePerGroupAndTransactionAndAfreshOutsideOne() {
        TenantId id = TenantId.of(UUID.randomUUID());
        provisioning.provision(
                id, new NewTenant("Settings Shop", null, "RETAIL", "LKR", "Asia/Colombo", 0, "STANDARD", null));

        java.util.List<java.util.Optional<String>> inOne = TenantContext.callWith(
                id,
                () -> new TransactionTemplate(transactions)
                        .execute(s -> java.util.List.of(
                                queries.setting("sales", "returnWindowDays"),
                                queries.setting("sales", "voidRequiresManager"),
                                queries.setting("payments", "drawerVarianceAlertAmount"),
                                queries.setting("nonsense", "key"))));

        assertThat(inOne)
                .containsExactly(
                        java.util.Optional.of("7"),
                        java.util.Optional.of("true"),
                        java.util.Optional.of("\"500.00\""),
                        java.util.Optional.empty());
        assertThat(TenantContext.callWith(id, () -> queries.setting("sales", "returnWindowDays")))
                .as("outside a transaction")
                .contains("7");
    }
}
