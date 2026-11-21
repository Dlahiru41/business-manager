package com.bmp.test.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.AbstractAssert;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fluent assertions on RFC 9457 Problem Details responses (04-error-handling.md §9):
 * {@code assertThatProblem(result).hasStatus(409).hasCode("INSUFFICIENT_STOCK")}.
 */
public final class ProblemAssert extends AbstractAssert<ProblemAssert, JsonNode> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> LEAK_MARKERS =
            List.of("Exception", "\tat ", "at com.", "at org.", "org.hibernate", "SQLState", "select ", "insert ");

    private final int status;
    private final String contentType;
    private final String raw;

    private ProblemAssert(int status, String contentType, String raw) {
        super(JSON.readTree(raw.isEmpty() ? "{}" : raw), ProblemAssert.class);
        this.status = status;
        this.contentType = contentType;
        this.raw = raw;
    }

    public static ProblemAssert assertThatProblem(MvcTestResult result) {
        var response = result.getResponse();
        String type = response.getContentType();
        return new ProblemAssert(
                response.getStatus(),
                type != null ? type : "",
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** For responses obtained from a real HTTP client. */
    public static ProblemAssert assertThatProblem(int status, String contentType, String body) {
        return new ProblemAssert(status, contentType, body);
    }

    public ProblemAssert hasStatus(int expected) {
        assertThat(status).as("HTTP status of %s", raw).isEqualTo(expected);
        assertThat(contentType).as("content type").startsWith("application/problem+json");
        assertThat(actual.path("status").asInt()).as("problem status member").isEqualTo(expected);
        return this;
    }

    public ProblemAssert hasCode(String expected) {
        assertThat(actual.path("code").asString()).as("problem code in %s", raw).isEqualTo(expected);
        return this;
    }

    /** Checks {@code type} ends with {@code /<module>/<kebab-code>}. */
    public ProblemAssert hasTypeEndingWith(String suffix) {
        assertThat(actual.path("type").asString()).as("problem type").endsWith(suffix);
        return this;
    }

    public ProblemAssert hasTitle(String expected) {
        assertThat(actual.path("title").asString()).as("problem title").isEqualTo(expected);
        return this;
    }

    public ProblemAssert hasDetailContaining(String expected) {
        assertThat(actual.path("detail").asString()).as("problem detail").contains(expected);
        return this;
    }

    public ProblemAssert hasDetail(String key, String expected) {
        assertThat(actual.path("details").path(key).asString()).as("details.%s", key).isEqualTo(expected);
        return this;
    }

    public ProblemAssert hasFieldError(String field, String code) {
        List<String> found = new ArrayList<>();
        actual.path("errors").forEach(e -> found.add(e.path("field").asString() + ":" + e.path("code").asString()));
        assertThat(found).as("field errors").contains(field + ":" + code);
        return this;
    }

    /** Every platform problem carries these members. */
    public ProblemAssert hasStandardMembers() {
        for (String member : List.of("type", "title", "status", "detail", "instance", "code", "timestamp")) {
            assertThat(actual.has(member)).as("member '%s' in %s", member, raw).isTrue();
        }
        return this;
    }

    /** No stack traces, class names or SQL leak to clients. */
    public ProblemAssert hasNoInternals() {
        for (String marker : LEAK_MARKERS) {
            assertThat(raw).as("body must not leak internals").doesNotContain(marker);
        }
        return this;
    }

    public JsonNode body() {
        return actual;
    }
}
