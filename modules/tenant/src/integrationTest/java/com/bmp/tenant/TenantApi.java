package com.bmp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.OpenApiValidation;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request helpers for the tenant API tests: tokens, idempotency keys and contract validation. */
final class TenantApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("tenant");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvcTester mvc;

    TenantApi(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    static String token(TenantId tenant, SystemRole role) {
        return JwtTestTokens.forRole(tenant, role);
    }

    MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header("Authorization", "Bearer " + token).exchange();
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

    MvcTestResult patch(String path, String token, String body, String ifMatch) {
        var request = mvc.patch()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return (ifMatch == null ? request : request.header("If-Match", ifMatch)).exchange();
    }

    /** Creates a tenant as platform administrator and returns its id. */
    TenantId createTenant(String name) {
        MvcTestResult result = post(
                "/api/v1/tenants",
                JwtTestTokens.platformAdmin(),
                "{\"name\": \"" + name + "\", \"businessType\": \"RETAIL_GROCERY\"}");
        assertThat(result).hasStatus(201);
        return TenantId.of(UUID.fromString(json(result).path("id").asString()));
    }

    static JsonNode json(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    static String etag(MvcTestResult result) {
        return result.getResponse().getHeader("ETag");
    }
}
