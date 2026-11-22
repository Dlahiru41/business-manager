package com.bmp.test.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.test.security.JwtTestTokens;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the platform's HTTP API the way a client does — bearer token, {@code Idempotency-Key} on every POST/PUT,
 * {@code If-Match} on updates — so API tests and fixtures go through the public API only (09 §4).
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvcTester mvc;

    public ApiClient(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public MvcTestResult get(String path, @Nullable String token) {
        var request = mvc.get().uri(path);
        return (token == null ? request : request.header("Authorization", "Bearer " + token)).exchange();
    }

    /** A conditional GET with {@code If-None-Match}. */
    public MvcTestResult getIfNoneMatch(String path, String token, String etag) {
        return mvc.get()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("If-None-Match", etag)
                .exchange();
    }

    public MvcTestResult post(String path, String token, String body) {
        return post(path, token, body, UUID.randomUUID().toString());
    }

    public MvcTestResult post(String path, String token, String body, String idempotencyKey) {
        return mvc.post()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    /** A POST action without a body (deactivate, publish). */
    public MvcTestResult action(String path, String token) {
        return action(path, token, UUID.randomUUID().toString());
    }

    public MvcTestResult action(String path, String token, String idempotencyKey) {
        return mvc.post()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .exchange();
    }

    public MvcTestResult patch(String path, String token, String body, @Nullable String ifMatch) {
        var request = mvc.patch()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return (ifMatch == null ? request : request.header("If-Match", ifMatch)).exchange();
    }

    public MvcTestResult put(String path, String token, String body, @Nullable String ifMatch) {
        return put(path, token, body, ifMatch, UUID.randomUUID().toString());
    }

    public MvcTestResult put(String path, String token, String body, @Nullable String ifMatch, String idempotencyKey) {
        var request = mvc.put()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return (ifMatch == null ? request : request.header("If-Match", ifMatch)).exchange();
    }

    public MvcTestResult delete(String path, String token) {
        return mvc.delete().uri(path).header("Authorization", "Bearer " + token).exchange();
    }

    /** Creates a tenant as platform administrator (Retail and Wholesale lists etc. are seeded by listeners). */
    public TenantId createTenant(String name) {
        MvcTestResult result = post(
                "/api/v1/tenants",
                JwtTestTokens.platformAdmin(),
                "{\"name\": \"" + name + "\", \"businessType\": \"RETAIL_GROCERY\"}");
        assertThat(result).hasStatus(201);
        return TenantId.of(UUID.fromString(json(result).path("id").asString()));
    }

    public static JsonNode json(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    /** The id field of a JSON response. */
    public static UUID id(MvcTestResult result) {
        return UUID.fromString(json(result).path("id").asString());
    }

    public static @Nullable String etag(MvcTestResult result) {
        return result.getResponse().getHeader("ETag");
    }
}
