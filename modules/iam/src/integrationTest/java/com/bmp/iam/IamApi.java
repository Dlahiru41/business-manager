package com.bmp.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.OpenApiValidation;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request helpers for the iam API tests: tenants, tokens, idempotency keys and contract validation. */
final class IamApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("iam");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SYSTEM_ROLES = 6;

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    IamApi(MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /** Runs SQL as the application role in the tenant's own context (row-level security applies). */
    <T> T sql(TenantId tenant, Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactionManager).execute(status -> query.apply(jdbc))));
    }

    static String token(TenantId tenant, SystemRole role) {
        return JwtTestTokens.forRole(tenant, role);
    }

    static String token(TenantId tenant, UserId user, String... roles) {
        return JwtTestTokens.builder().tenant(tenant).user(user).roles(roles).build();
    }

    static String deviceToken(TenantId tenant, DeviceId device) {
        return JwtTestTokens.builder()
                .tenant(tenant)
                .device(device)
                .roles(SystemRole.DEVICE.code())
                .build();
    }

    MvcTestResult get(String path, @Nullable String token) {
        var request = mvc.get().uri(path);
        return (token == null ? request : request.header("Authorization", "Bearer " + token)).exchange();
    }

    MvcTestResult post(String path, String token, String body) {
        return post(path, token, body, UUID.randomUUID().toString());
    }

    MvcTestResult post(String path, String token, String body, String idempotencyKey) {
        return mvc.post()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    MvcTestResult patch(String path, String token, String body, @Nullable String ifMatch) {
        var request = mvc.patch()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return (ifMatch == null ? request : request.header("If-Match", ifMatch)).exchange();
    }

    MvcTestResult put(String path, String token, String body, @Nullable String ifMatch) {
        var request = mvc.put()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return (ifMatch == null ? request : request.header("If-Match", ifMatch)).exchange();
    }

    /** Creates a tenant as platform administrator and waits until iam has seeded its system roles. */
    TenantId createTenant(String name) {
        MvcTestResult result = post(
                "/api/v1/tenants",
                JwtTestTokens.platformAdmin(),
                "{\"name\": \"" + name + "\", \"businessType\": \"RETAIL_GROCERY\"}");
        assertThat(result).hasStatus(201);
        TenantId tenant = TenantId.of(UUID.fromString(json(result).path("id").asString()));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(this.<Long>sql(
                                tenant,
                                db -> db.sql("SELECT count(*) FROM iam.role WHERE system")
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(SYSTEM_ROLES));
        return tenant;
    }

    /** The tenant's main location, created with the tenant. */
    UUID mainLocation(TenantId tenant) {
        MvcTestResult locations = get("/api/v1/locations", token(tenant, SystemRole.OWNER));
        assertThat(locations).hasStatus(200);
        return UUID.fromString(json(locations).path("items").get(0).path("id").asString());
    }

    /** Creates a user through the API and returns the response. */
    MvcTestResult createUser(TenantId tenant, String username, String rolesJson) {
        return post(
                "/api/v1/users",
                token(tenant, SystemRole.OWNER),
                "{\"username\": \"" + username + "\", \"displayName\": \"" + username + "\", \"roles\": " + rolesJson
                        + "}");
    }

    static JsonNode json(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    static String etag(MvcTestResult result) {
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag).isNotNull();
        return etag;
    }

    /** The ETag names the version in the body, so a client can send it back in {@code If-Match}. */
    static void assertEtagIsVersion(MvcTestResult result) {
        assertThat(result.getResponse().getHeader("ETag"))
                .isEqualTo("W/\"" + json(result).path("version").asLong() + "\"");
    }

    static UUID id(MvcTestResult result) {
        return UUID.fromString(json(result).path("id").asString());
    }
}
