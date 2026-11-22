package com.bmp.iam;

import static com.bmp.iam.IamApi.CONTRACT;
import static com.bmp.iam.IamApi.etag;
import static com.bmp.iam.IamApi.id;
import static com.bmp.iam.IamApi.json;
import static com.bmp.iam.IamApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.WithPostgres;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/** Users through the API: creation in the identity provider, updates, roles and IAM-01. */
@SpringBootTest(classes = IamTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class UserApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    FakeIdentityProvider identityProvider;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private IamApi api;
    private TenantId tenant;
    private String owner;

    @BeforeEach
    void setUp() {
        api = new IamApi(mvc, jdbc, transactionManager);
        tenant = api.createTenant("Users " + UUID.randomUUID());
        owner = token(tenant, SystemRole.OWNER);
    }

    @AfterEach
    void resetIdentityProvider() {
        identityProvider.reset();
    }

    private static String unique(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsTheAccountInTheIdentityProviderAndTheProfileLocally() {
        UUID location = api.mainLocation(tenant);
        String username = unique("nimal");
        MvcTestResult created = api.post("/api/v1/users", owner, """
                {"username": "%s", "email": "nimal@example.lk", "displayName": "Nimal",
                 "defaultLocationId": "%s",
                 "roles": [{"code": "CASHIER", "locationScope": ["%s"]}]}
                """.formatted(username, location, location));

        assertThat(created).hasStatus(201);
        IamApi.assertEtagIsVersion(created);
        CONTRACT.assertConforms(created);
        UUID userId = id(created);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/users/" + userId);
        assertThat(json(created).path("idpSubject").asString()).isEqualTo(userId.toString());
        assertThat(json(created).path("roles").get(0).path("code").asString()).isEqualTo("CASHIER");

        FakeIdentityProvider.Account account = identityProvider.account(UserId.of(userId));
        assertThat(account).isNotNull();
        assertThat(account.tenant()).isEqualTo(tenant.toString());
        assertThat(account.roles()).containsExactly("CASHIER");
        assertThat(account.email()).isEqualTo("nimal@example.lk");

        MvcTestResult fetched = api.get("/api/v1/users/" + userId, owner);
        assertThat(fetched).hasStatus(200);
        CONTRACT.assertConforms(fetched);
        assertThat(json(fetched).path("defaultLocationId").asString()).isEqualTo(location.toString());

        MvcTestResult page = api.get("/api/v1/users?limit=1", owner);
        assertThat(page).hasStatus(200);
        CONTRACT.assertConforms(page);
        assertThat(json(page).path("items")).hasSize(1);
    }

    @Test
    void pagesThroughUsersWithACursor() {
        api.createUser(tenant, unique("a"), "[{\"code\": \"CASHIER\"}]");
        api.createUser(tenant, unique("b"), "[{\"code\": \"CASHIER\"}]");

        MvcTestResult first = api.get("/api/v1/users?limit=1", owner);
        String cursor = json(first).path("page").path("nextCursor").asString();
        MvcTestResult second = api.get("/api/v1/users?limit=1&cursor=" + cursor, owner);
        MvcTestResult broken = api.get("/api/v1/users?cursor=bm90LWEtY3Vyc29y", owner);

        assertThat(json(first).path("page").path("hasMore").asBoolean()).isTrue();
        assertThat(json(second).path("items").get(0).path("id"))
                .isNotEqualTo(json(first).path("items").get(0).path("id"));
        assertThatProblem(broken).hasStatus(400).hasCode("INVALID_PARAMETER");
    }

    @Test
    void replaysCreationWithTheSameIdempotencyKey() {
        String key = UUID.randomUUID().toString();
        String body = "{\"username\": \"" + unique("replay") + "\", \"displayName\": \"R\", \"roles\": "
                + "[{\"code\": \"CASHIER\"}]}";

        MvcTestResult first = api.post("/api/v1/users", owner, body, key);
        MvcTestResult replay = api.post("/api/v1/users", owner, body, key);

        assertThat(first).hasStatus(201);
        assertThat(replay).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(id(replay)).isEqualTo(id(first));
    }

    @Test
    void rejectsUnknownRolesLocationsAndTakenUsernames() {
        String username = unique("taken");
        assertThat(api.createUser(tenant, username, "[{\"code\": \"CASHIER\"}]"))
                .hasStatus(201);

        MvcTestResult unknownRole = api.createUser(tenant, unique("x"), "[{\"code\": \"NO_SUCH_ROLE\"}]");
        MvcTestResult duplicateRole =
                api.createUser(tenant, unique("x"), "[{\"code\": \"CASHIER\"}, {\"code\": \"CASHIER\"}]");
        MvcTestResult badLocation = api.createUser(
                tenant, unique("x"), "[{\"code\": \"CASHIER\", \"locationScope\": [\"" + UUID.randomUUID() + "\"]}]");
        MvcTestResult taken = api.createUser(tenant, username, "[{\"code\": \"CASHIER\"}]");
        MvcTestResult invalid = api.post("/api/v1/users", owner, "{\"username\": \"A\", \"roles\": []}");

        assertThatProblem(unknownRole).hasStatus(422).hasCode("UNKNOWN_ROLE");
        assertThatProblem(duplicateRole).hasStatus(422).hasCode("VALIDATION_FAILED");
        assertThatProblem(badLocation).hasStatus(422).hasCode("INVALID_LOCATION");
        assertThatProblem(taken).hasStatus(409).hasCode("USERNAME_TAKEN");
        assertThatProblem(invalid).hasStatus(422).hasCode("VALIDATION_FAILED");
        CONTRACT.assertConforms(unknownRole);
        CONTRACT.assertConforms(taken);
    }

    @Test
    void answers503AndStoresNothingWhenTheIdentityProviderIsDown() {
        identityProvider.failOn("create-user");

        MvcTestResult result = api.createUser(tenant, unique("down"), "[{\"code\": \"CASHIER\"}]");

        assertThatProblem(result)
                .hasStatus(503)
                .hasCode("IDENTITY_PROVIDER_UNAVAILABLE")
                .hasNoInternals();
        CONTRACT.assertConforms(result);
        assertThat(json(api.get("/api/v1/users", owner)).path("items")).isEmpty();
    }

    @Test
    void deletesTheAccountAgainWhenTheLocalProfileCannotBeStored() {
        MvcTestResult first = api.createUser(tenant, unique("first"), "[{\"code\": \"CASHIER\"}]");
        UserId existing = UserId.of(id(first));
        identityProvider.nextSubject(existing);
        String clashing = unique("clash");

        MvcTestResult clash = api.createUser(tenant, clashing, "[{\"code\": \"CASHIER\"}]");

        assertThat(clash.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(identityProvider.account(existing))
                .as("the clashing account was removed again, here under the same subject")
                .isNull();
    }

    @Test
    void updatesDisplayNameStatusAndDefaultLocationWithIfMatch() {
        MvcTestResult created = api.createUser(tenant, unique("upd"), "[{\"code\": \"CASHIER\"}]");
        UUID userId = id(created);
        UUID location = api.mainLocation(tenant);

        MvcTestResult missingIfMatch = api.patch("/api/v1/users/" + userId, owner, "{\"displayName\": \"New\"}", null);
        MvcTestResult updated = api.patch(
                "/api/v1/users/" + userId,
                owner,
                "{\"displayName\": \"New\", \"status\": \"DISABLED\", \"defaultLocationId\": \"" + location + "\"}",
                etag(created));
        MvcTestResult stale = api.patch("/api/v1/users/" + userId, owner, "{\"displayName\": \"Old\"}", etag(created));
        MvcTestResult reenabled =
                api.patch("/api/v1/users/" + userId, owner, "{\"status\": \"ACTIVE\"}", etag(updated));

        assertThatProblem(missingIfMatch).hasStatus(428).hasCode("PRECONDITION_REQUIRED");
        assertThat(updated).hasStatus(200);
        IamApi.assertEtagIsVersion(updated);
        assertThat(json(updated).path("version").asLong())
                .isEqualTo(json(created).path("version").asLong() + 1);
        CONTRACT.assertConforms(updated);
        assertThat(json(updated).path("status").asString()).isEqualTo("DISABLED");
        assertThat(json(updated).path("displayName").asString()).isEqualTo("New");
        assertThatProblem(stale).hasStatus(412).hasCode("VERSION_CONFLICT");
        assertThat(reenabled).hasStatus(200);
        assertThat(identityProvider.account(UserId.of(userId)).enabled()).isTrue();
    }

    @Test
    void rejectsAnUnknownDefaultLocation() {
        MvcTestResult created = api.createUser(tenant, unique("loc"), "[{\"code\": \"CASHIER\"}]");

        MvcTestResult result = api.patch(
                "/api/v1/users/" + id(created),
                owner,
                "{\"defaultLocationId\": \"" + UUID.randomUUID() + "\"}",
                etag(created));

        assertThatProblem(result).hasStatus(422).hasCode("INVALID_LOCATION");
    }

    @Test
    void keepsTheLastActiveOwner() {
        MvcTestResult first = api.createUser(tenant, unique("owner1"), "[{\"code\": \"OWNER\"}]");
        UUID ownerId = id(first);

        MvcTestResult disable = api.patch("/api/v1/users/" + ownerId, owner, "{\"status\": \"DISABLED\"}", etag(first));
        MvcTestResult demote = api.put(
                "/api/v1/users/" + ownerId + "/roles", owner, "{\"roles\": [{\"code\": \"MANAGER\"}]}", etag(first));

        assertThatProblem(disable).hasStatus(409).hasCode("LAST_OWNER");
        assertThatProblem(demote).hasStatus(409).hasCode("LAST_OWNER");
        CONTRACT.assertConforms(disable);
        assertThat(identityProvider.account(UserId.of(ownerId)).enabled()).isTrue();
        assertThat(identityProvider.account(UserId.of(ownerId)).roles()).containsExactly("OWNER");

        MvcTestResult second = api.createUser(tenant, unique("owner2"), "[{\"code\": \"OWNER\"}]");
        MvcTestResult demoteWithAnotherOwner = api.put(
                "/api/v1/users/" + ownerId + "/roles",
                owner,
                "{\"roles\": [{\"code\": \"MANAGER\"}, {\"code\": \"CASHIER\"}]}",
                etag(first));
        MvcTestResult disableTheOther =
                api.patch("/api/v1/users/" + id(second), owner, "{\"status\": \"DISABLED\"}", etag(second));

        assertThat(demoteWithAnotherOwner).hasStatus(200);
        CONTRACT.assertConforms(demoteWithAnotherOwner);
        assertThat(identityProvider.account(UserId.of(ownerId)).roles()).containsExactly("MANAGER", "CASHIER");
        assertThatProblem(disableTheOther).hasStatus(409).hasCode("LAST_OWNER");
    }

    @Test
    void leavesRolesUnchangedWhenTheIdentityProviderFails() {
        MvcTestResult created = api.createUser(tenant, unique("restore"), "[{\"code\": \"CASHIER\"}]");
        UUID userId = id(created);
        identityProvider.failOn("set-roles");

        MvcTestResult failed = api.put(
                "/api/v1/users/" + userId + "/roles", owner, "{\"roles\": [{\"code\": \"MANAGER\"}]}", etag(created));

        assertThatProblem(failed).hasStatus(503).hasCode("IDENTITY_PROVIDER_UNAVAILABLE");
        assertThat(json(api.get("/api/v1/users/" + userId, owner))
                        .path("roles")
                        .get(0)
                        .path("code")
                        .asString())
                .isEqualTo("CASHIER");
    }

    @Test
    void anotherTenantCannotSeeOrChangeTheUser() {
        UUID userId = id(api.createUser(tenant, unique("iso"), "[{\"code\": \"CASHIER\"}]"));
        TenantId other = api.createTenant("Users other " + UUID.randomUUID());
        String otherOwner = token(other, SystemRole.OWNER);

        MvcTestResult read = api.get("/api/v1/users/" + userId, otherOwner);
        MvcTestResult write = api.patch("/api/v1/users/" + userId, otherOwner, "{\"displayName\": \"x\"}", "\"0\"");

        assertThatProblem(read).hasStatus(404).hasCode("USER_NOT_FOUND");
        assertThatProblem(write).hasStatus(404).hasCode("USER_NOT_FOUND");
        assertThat(json(api.get("/api/v1/users", otherOwner)).path("items")).isEmpty();
    }

    @Test
    void requiresAuthenticationAndThePermission() {
        MvcTestResult anonymous = api.get("/api/v1/users", null);
        MvcTestResult cashier = api.get("/api/v1/users", token(tenant, SystemRole.CASHIER));

        assertThatProblem(anonymous).hasStatus(401).hasCode("UNAUTHENTICATED");
        assertThatProblem(cashier).hasStatus(403).hasCode("ACCESS_DENIED");
        CONTRACT.assertConforms(cashier);
    }

    @Test
    void adoptsAnAccountOfThisTenantLeftWithoutAProfile() {
        String username = unique("orphan");
        UserId orphan = identityProvider.createUser(
                new IdentityProviderGateway.NewIdentity(username, null, "Orphan", tenant, List.of()));

        MvcTestResult created = api.createUser(tenant, username, "[{\"code\": \"CASHIER\"}]");

        assertThat(created).hasStatus(201);
        assertThat(id(created)).isEqualTo(orphan.value());
        assertThat(identityProvider.account(orphan).enabled()).isTrue();
        assertThat(identityProvider.account(orphan).roles()).containsExactly("CASHIER");
    }

    @Test
    void neverAdoptsAnAccountOfAnotherTenant() {
        String username = unique("foreign");
        identityProvider.createUser(new IdentityProviderGateway.NewIdentity(
                username, null, "Foreign", TenantId.of(UUID.randomUUID()), List.of()));

        assertThatProblem(api.createUser(tenant, username, "[{\"code\": \"CASHIER\"}]"))
                .hasStatus(409)
                .hasCode("USERNAME_TAKEN");
    }

    @Test
    void keepsTheProfileDisabledWhenTheAccountCannotBeEnabled() {
        identityProvider.failOn("activate-user");
        String username = unique("half");

        MvcTestResult result = api.createUser(tenant, username, "[{\"code\": \"CASHIER\"}]");

        assertThatProblem(result).hasStatus(503).hasCode("IDENTITY_PROVIDER_UNAVAILABLE");
        JsonNode user = json(api.get("/api/v1/users", owner)).path("items").get(0);
        assertThat(user.path("status").asString()).isEqualTo("DISABLED");
        assertThat(identityProvider
                        .account(UserId.of(UUID.fromString(user.path("id").asString())))
                        .enabled())
                .isFalse();
    }

    @Test
    void nobodyGrantsOrChangesMoreThanTheyHold() {
        api.post(
                "/api/v1/roles",
                owner,
                "{\"code\": \"STAFF_ADMIN\", \"name\": \"Staff admin\", "
                        + "\"permissions\": [\"iam:users\", \"iam:roles\", \"sales:*\"]}");
        api.post(
                "/api/v1/roles",
                owner,
                "{\"code\": \"SELLER\", \"name\": \"Seller\", \"permissions\": [\"sales:create\"]}");
        String staffAdmin = token(tenant, UserId.of(UUID.randomUUID()), "STAFF_ADMIN");
        MvcTestResult anOwner = api.createUser(tenant, unique("own"), "[{\"code\": \"OWNER\"}]");

        MvcTestResult grantOwner = api.post(
                "/api/v1/users",
                staffAdmin,
                "{\"username\": \"" + unique("x") + "\", \"displayName\": \"X\", \"roles\": [{\"code\": \"OWNER\"}]}");
        MvcTestResult grantCashier = api.post(
                "/api/v1/users",
                staffAdmin,
                "{\"username\": \"" + unique("x")
                        + "\", \"displayName\": \"X\", \"roles\": [{\"code\": \"CASHIER\"}]}");
        MvcTestResult grantSeller = api.post(
                "/api/v1/users",
                staffAdmin,
                "{\"username\": \"" + unique("x") + "\", \"displayName\": \"X\", \"roles\": [{\"code\": \"SELLER\"}]}");
        MvcTestResult disableOwner =
                api.patch("/api/v1/users/" + id(anOwner), staffAdmin, "{\"status\": \"DISABLED\"}", etag(anOwner));
        MvcTestResult roleWithEverything = api.post(
                "/api/v1/roles",
                staffAdmin,
                "{\"code\": \"ALMIGHTY\", \"name\": \"Almighty\", \"permissions\": [\"*\"]}");
        MvcTestResult roleWithin = api.post(
                "/api/v1/roles",
                staffAdmin,
                "{\"code\": \"VIEWER\", \"name\": \"Viewer\", \"permissions\": [\"sales:read\"]}");
        MvcTestResult deviceRole = api.createUser(tenant, unique("dev"), "[{\"code\": \"DEVICE\"}]");

        assertThatProblem(grantOwner).hasStatus(403).hasCode("ROLE_GRANT_EXCEEDS_CALLER");
        CONTRACT.assertConforms(grantOwner);
        assertThatProblem(grantCashier)
                .as("the cashier role holds catalog:read")
                .hasStatus(403);
        assertThat(grantSeller).hasStatus(201);
        assertThatProblem(disableOwner).hasStatus(403).hasCode("ROLE_GRANT_EXCEEDS_CALLER");
        assertThatProblem(roleWithEverything).hasStatus(403).hasCode("ROLE_GRANT_EXCEEDS_CALLER");
        assertThat(roleWithin).hasStatus(201);
        assertThatProblem(deviceRole)
                .as("the device role is for devices")
                .hasStatus(422)
                .hasCode("UNKNOWN_ROLE");
    }
}
