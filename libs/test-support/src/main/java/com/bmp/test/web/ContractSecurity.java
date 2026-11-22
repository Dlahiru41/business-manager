package com.bmp.test.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves every operation of a module contract is protected as its {@code x-permission} says (03-api-standards.md §10,
 * 08-security.md §10): no token is 401, a token without the permission is 403 {@code ACCESS_DENIED}, and the
 * controller method declares exactly that permission in {@code @PreAuthorize}. Requests use the contract's own
 * examples, so they pass validation and reach the authorization check.
 *
 * <pre>{@code ContractSecurity.assertEveryOperationIsProtected("iam", mvc, context, tokenWithoutPermissions);}</pre>
 */
public final class ContractSecurity {

    /** {@code x-permission} value of operations that only need an authenticated caller. */
    public static final String AUTHENTICATED = "authenticated";

    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContractSecurity() {}

    /** One operation of the contract, ready to be sent. */
    record Operation(
            String id,
            HttpMethod method,
            String uri,
            String permission,
            @Nullable String body) {}

    /**
     * Checks every operation of {@code contracts/openapi/<module>.yaml}.
     *
     * @param tokenWithoutPermissions a valid token of an active tenant whose roles grant nothing
     */
    public static void assertEveryOperationIsProtected(
            String module, MockMvcTester mvc, ApplicationContext context, String tokenWithoutPermissions) {
        List<Operation> operations = operations(module);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(operations).as("operations of %s.yaml", module).isNotEmpty();
        for (Operation operation : operations) {
            MvcTestResult anonymous = send(mvc, operation, null);
            softly.assertThat(anonymous.getResponse().getStatus())
                    .as("%s %s without a token", operation.method(), operation.uri())
                    .isEqualTo(401);
            if (!AUTHENTICATED.equals(operation.permission())) {
                MvcTestResult refused = send(mvc, operation, tokenWithoutPermissions);
                softly.assertThat(refused.getResponse().getStatus())
                        .as("%s %s without %s", operation.method(), operation.uri(), operation.permission())
                        .isEqualTo(403);
                softly.assertThat(contentOf(refused))
                        .as("%s %s problem code", operation.method(), operation.uri())
                        .contains("\"code\":\"ACCESS_DENIED\"");
            }
            softly.assertThat(declaredRule(context, operation.id()))
                    .as("@PreAuthorize of %s", operation.id())
                    .isEqualTo(expectedRule(operation.permission()));
        }
        softly.assertAll();
    }

    private static MvcTestResult send(MockMvcTester mvc, Operation operation, @Nullable String token) {
        var request = mvc.method(operation.method())
                .uri(operation.uri())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "W/\"0\"");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        String body = operation.body();
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static String contentOf(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).replace(" ", "");
    }

    private static String expectedRule(String permission) {
        return AUTHENTICATED.equals(permission) ? "isAuthenticated()" : "hasPermission(null, '" + permission + "')";
    }

    private static String declaredRule(ApplicationContext context, String operationId) {
        for (Object controller :
                context.getBeansWithAnnotation(RestController.class).values()) {
            for (Method method : AopUtils.getTargetClass(controller).getDeclaredMethods()) {
                if (method.getName().equals(operationId)) {
                    PreAuthorize rule = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
                    return rule != null ? rule.value() : "<none>";
                }
            }
        }
        return "<no controller method>";
    }

    static List<Operation> operations(String module) {
        JsonNode contract = read(module);
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : contract.path("paths").properties()) {
            for (String method : METHODS) {
                JsonNode operation = path.getValue().path(method);
                if (operation.isMissingNode()) {
                    continue;
                }
                operations.add(new Operation(
                        operation.path("operationId").asText(),
                        HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)),
                        uri(contract, path.getKey(), operation),
                        operation.path("x-permission").asText(),
                        body(contract, operation)));
            }
        }
        return operations;
    }

    /** Fills path parameters with a UUID, the first enum value, the schema example, or a lower-case word. */
    private static String uri(JsonNode contract, String template, JsonNode operation) {
        String uri = template;
        for (JsonNode parameter : operation.path("parameters")) {
            JsonNode resolved = resolve(contract, parameter);
            if (!"path".equals(resolved.path("in").asText())) {
                continue;
            }
            JsonNode schema = resolved.path("schema");
            String value;
            if ("uuid".equals(schema.path("format").asText())) {
                value = UUID.randomUUID().toString();
            } else if (schema.has("enum")) {
                value = schema.path("enum").get(0).asText();
            } else if (schema.has("example")) {
                value = schema.path("example").asText();
            } else {
                value = "sample";
            }
            uri = uri.replace("{" + resolved.path("name").asText() + "}", value);
        }
        return uri;
    }

    private static @Nullable String body(JsonNode contract, JsonNode operation) {
        JsonNode json = operation.path("requestBody").path("content").path("application/json");
        if (json.isMissingNode()) {
            return null;
        }
        JsonNode example = json.has("example") ? json.path("example") : firstExample(contract, json.path("examples"));
        try {
            return JSON.writeValueAsString(example);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode firstExample(JsonNode contract, JsonNode examples) {
        for (JsonNode example : examples) {
            return resolve(contract, example).path("value");
        }
        return JSON.createObjectNode();
    }

    /** Resolves a local {@code #/components/...} reference; references to other files stay as they are. */
    private static JsonNode resolve(JsonNode contract, JsonNode node) {
        String ref = node.path("$ref").asText("");
        if (!ref.startsWith("#/")) {
            return node;
        }
        return contract.at(ref.substring(1));
    }

    private static JsonNode read(String module) {
        String contracts = System.getProperty("bmp.contracts.dir", "contracts");
        try {
            return new YAMLMapper()
                    .readTree(Path.of(contracts, "openapi", module + ".yaml").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
