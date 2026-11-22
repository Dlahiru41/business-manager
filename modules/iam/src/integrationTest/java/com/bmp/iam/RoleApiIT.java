package com.bmp.iam;

import static com.bmp.iam.IamApi.CONTRACT;
import static com.bmp.iam.IamApi.etag;
import static com.bmp.iam.IamApi.id;
import static com.bmp.iam.IamApi.json;
import static com.bmp.iam.IamApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.iam.api.IamProvisioning;
import com.bmp.iam.api.dto.ExistingAccount;
import com.bmp.iam.application.command.SeedSystemRolesHandler;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.WithPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** System role seeding (on TenantCreated), custom roles, IAM-02 and the effect of roles on permissions. */
@SpringBootTest(classes = IamTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class RoleApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    SeedSystemRolesHandler seeder;

    @Autowired
    IamProvisioning provisioning;

    private IamApi api;
    private TenantId tenant;
    private String owner;

    @BeforeEach
    void setUp() {
        api = new IamApi(mvc, jdbc, transactionManager);
        tenant = api.createTenant("Roles " + UUID.randomUUID());
        owner = token(tenant, SystemRole.OWNER);
    }

    private JsonNode role(String code) {
        for (JsonNode role : json(api.get("/api/v1/roles", owner)).path("items")) {
            if (role.path("code").asString().equals(code)) {
                return role;
            }
        }
        throw new AssertionError("no role " + code);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private List<String> permissionsOf(String token) {
        MvcTestResult result = api.get("/api/v1/me/permissions", token);
        assertThat(result).hasStatus(200);
        return strings(json(result).path("permissions"));
    }

    @Test
    void seedsEverySystemRoleWithItsDefaultPermissionsOnce() {
        MvcTestResult roles = api.get("/api/v1/roles", owner);

        assertThat(roles).hasStatus(200);
        CONTRACT.assertConforms(roles);
        List<String> codes = new ArrayList<>();
        json(roles).path("items").forEach(role -> codes.add(role.path("code").asString()));
        assertThat(codes)
                .containsExactlyInAnyOrder("OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "ACCOUNTANT", "DEVICE");
        assertThat(strings(role("OWNER").path("permissions"))).containsExactly("*");
        assertThat(strings(role("CASHIER").path("permissions")))
                .containsExactlyInAnyOrderElementsOf(SystemRole.CASHIER.defaultPermissions());
        assertThat(role("MANAGER").path("system").asBoolean()).isTrue();

        int createdAgain = TenantContext.callWith(tenant, seeder::seed);
        assertThat(createdAgain).as("seeding is idempotent").isZero();
    }

    @Test
    void createsCustomRolesWhosePermissionsApplyToTokens() {
        MvcTestResult created = api.post(
                "/api/v1/roles",
                owner,
                "{\"code\": \"SUPERVISOR\", \"name\": \"Shift supervisor\", \"permissions\": [\"sales:*\", \"catalog:read\"]}");

        assertThat(created).hasStatus(201);
        IamApi.assertEtagIsVersion(created);
        CONTRACT.assertConforms(created);
        UUID roleId = id(created);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/roles/" + roleId);
        String supervisor = token(tenant, UserId.of(UUID.randomUUID()), "SUPERVISOR");
        assertThat(permissionsOf(supervisor)).containsExactly("catalog:read", "sales:*");

        MvcTestResult updated = api.patch(
                "/api/v1/roles/" + roleId,
                owner,
                "{\"name\": \"Supervisor\", \"permissions\": [\"sales:read\"]}",
                etag(created));
        assertThat(updated).hasStatus(200);
        CONTRACT.assertConforms(updated);
        assertThat(permissionsOf(supervisor)).as("evicted after commit").containsExactly("sales:read");

        MvcTestResult deactivated =
                api.patch("/api/v1/roles/" + roleId, owner, "{\"status\": \"INACTIVE\"}", etag(updated));
        assertThat(deactivated).hasStatus(200);
        assertThat(permissionsOf(supervisor)).isEmpty();

        MvcTestResult reactivated =
                api.patch("/api/v1/roles/" + roleId, owner, "{\"status\": \"ACTIVE\"}", etag(deactivated));
        assertThat(json(reactivated).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(api.get("/api/v1/roles/" + roleId, owner)).hasStatus(200);
    }

    @Test
    void rejectsTakenReservedAndPlatformGrants() {
        api.post("/api/v1/roles", owner, "{\"code\": \"PACKER\", \"name\": \"Packer\", \"permissions\": []}");

        MvcTestResult taken =
                api.post("/api/v1/roles", owner, "{\"code\": \"PACKER\", \"name\": \"Packer\", \"permissions\": []}");
        MvcTestResult reserved =
                api.post("/api/v1/roles", owner, "{\"code\": \"OWNER\", \"name\": \"Owner 2\", \"permissions\": []}");
        MvcTestResult platform = api.post(
                "/api/v1/roles",
                owner,
                "{\"code\": \"SNEAKY\", \"name\": \"Sneaky\", \"permissions\": [\"platform:tenants\"]}");
        MvcTestResult malformed = api.post(
                "/api/v1/roles", owner, "{\"code\": \"lower\", \"name\": \"Lower\", \"permissions\": [\"x\"]}");

        assertThatProblem(taken).hasStatus(409).hasCode("ROLE_CODE_TAKEN");
        assertThatProblem(reserved).hasStatus(409).hasCode("ROLE_CODE_TAKEN");
        assertThatProblem(platform).hasStatus(422).hasCode("INVALID_PERMISSION");
        assertThatProblem(malformed).hasStatus(422).hasCode("VALIDATION_FAILED");
        CONTRACT.assertConforms(taken);
        CONTRACT.assertConforms(platform);
    }

    @Test
    void neverDeactivatesSystemRolesOrNarrowsTheOwner() {
        JsonNode cashier = role("CASHIER");
        JsonNode ownerRole = role("OWNER");
        String cashierPath = "/api/v1/roles/" + cashier.path("id").asString();
        String cashierEtag = "\"" + cashier.path("version").asLong() + "\"";

        MvcTestResult deactivate = api.patch(cashierPath, owner, "{\"status\": \"INACTIVE\"}", cashierEtag);
        MvcTestResult narrowOwner = api.patch(
                "/api/v1/roles/" + ownerRole.path("id").asString(),
                owner,
                "{\"permissions\": [\"sales:read\"]}",
                "\"" + ownerRole.path("version").asLong() + "\"");
        MvcTestResult extendCashier =
                api.patch(cashierPath, owner, "{\"permissions\": [\"sales:create\", \"tenant:read\"]}", cashierEtag);

        assertThatProblem(deactivate).hasStatus(409).hasCode("SYSTEM_ROLE_IMMUTABLE");
        assertThatProblem(narrowOwner).hasStatus(409).hasCode("SYSTEM_ROLE_IMMUTABLE");
        CONTRACT.assertConforms(deactivate);
        assertThat(extendCashier).hasStatus(200);
        assertThat(permissionsOf(token(tenant, SystemRole.CASHIER))).containsExactly("sales:create", "tenant:read");
    }

    @Test
    void checksVersionsAndExistence() {
        JsonNode manager = role("MANAGER");
        String path = "/api/v1/roles/" + manager.path("id").asString();

        MvcTestResult stale = api.patch(path, owner, "{\"name\": \"Boss\"}", "\"99\"");
        MvcTestResult missing = api.get("/api/v1/roles/" + UUID.randomUUID(), owner);
        MvcTestResult missingUpdate =
                api.patch("/api/v1/roles/" + UUID.randomUUID(), owner, "{\"name\": \"X\"}", "\"0\"");
        MvcTestResult forbidden = api.get("/api/v1/roles", token(tenant, SystemRole.MANAGER));

        assertThatProblem(stale).hasStatus(412).hasCode("VERSION_CONFLICT");
        assertThatProblem(missing).hasStatus(404).hasCode("ROLE_NOT_FOUND");
        assertThatProblem(missingUpdate).hasStatus(404).hasCode("ROLE_NOT_FOUND");
        assertThatProblem(forbidden).hasStatus(403).hasCode("ACCESS_DENIED");
        CONTRACT.assertConforms(missing);
    }

    @Test
    void provisionsExistingAccountsOnceWithoutTheIdentityProvider() {
        UserId subject = UserId.of(UUID.randomUUID());
        ExistingAccount account = new ExistingAccount(subject, "Seeded owner", List.of("OWNER", "CASHIER"));

        provisioning.provision(tenant, account);
        provisioning.provision(tenant, new ExistingAccount(subject, "Renamed", List.of("CASHIER")));

        JsonNode user = json(api.get("/api/v1/users/" + subject, owner));
        assertThat(user.path("displayName").asString()).isEqualTo("Seeded owner");
        assertThat(user.path("roles")).hasSize(2);
        assertThatThrownBy(() -> provisioning.provision(
                        tenant, new ExistingAccount(UserId.of(UUID.randomUUID()), "X", List.of("NO_SUCH_ROLE"))))
                .isInstanceOfSatisfying(
                        BmpException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("UNKNOWN_ROLE"));
    }
}
