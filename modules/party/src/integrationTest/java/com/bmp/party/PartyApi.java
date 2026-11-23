package com.bmp.party;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.OpenApiValidation;
import java.util.Objects;
import java.util.UUID;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** Request helpers for the party API tests: a fresh tenant, its owner, and contract validation of every response. */
final class PartyApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("party");

    final ApiClient client;
    final TenantId tenant;
    final String owner;

    PartyApi(MockMvcTester mvc, String name) {
        this.client = new ApiClient(mvc);
        this.tenant = client.createTenant(name + " " + UUID.randomUUID());
        this.owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
    }

    String token(SystemRole role) {
        return JwtTestTokens.forRole(tenant, role);
    }

    MvcTestResult get(String path) {
        return checked(client.get(path, owner));
    }

    MvcTestResult post(String path, String body) {
        return checked(client.post(path, owner, body));
    }

    MvcTestResult post(String path, String token, String body) {
        return checked(client.post(path, token, body));
    }

    MvcTestResult patch(String path, String body, String ifMatch) {
        return checked(client.patch(path, owner, body, ifMatch));
    }

    MvcTestResult put(String path, String body, String ifMatch) {
        return checked(client.put(path, owner, body, ifMatch));
    }

    /** Creates a party and returns its JSON. */
    JsonNode party(String body) {
        return json(post("/api/v1/parties", body));
    }

    static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    static String etag(MvcTestResult result) {
        return Objects.requireNonNull(ApiClient.etag(result), "ETag");
    }

    static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
