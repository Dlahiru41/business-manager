package com.bmp.web;

import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.web.testapp.DummyController;
import com.bmp.web.testapp.WebSupportTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(classes = WebSupportTestApplication.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailsApiTest {

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult fail(String kind) {
        return mvc.get().uri("/test/errors/{kind}", kind).exchange();
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({
        "validation,         422, NAME_INVALID,            /test/name-invalid",
        "fields,             422, VALIDATION_FAILED,       /platform/validation-failed",
        "not-found,          404, THING_NOT_FOUND,         /test/thing-not-found",
        "conflict,           409, THING_LOCKED,            /test/thing-locked",
        "forbidden,          403, ACCESS_DENIED,           /platform/access-denied",
        "precondition,       412, VERSION_CONFLICT,        /platform/version-conflict",
        "rate-limited,       429, RATE_LIMIT_EXCEEDED,     /platform/rate-limit-exceeded",
        "dependency,         503, PRINTER_UNREACHABLE,     /test/printer-unreachable",
        "internal,           500, INTERNAL_ERROR,          /platform/internal-error",
        "unexpected,         500, INTERNAL_ERROR,          /platform/internal-error",
        "unique,             409, THING_NAME_TAKEN,        /test/thing-name-taken",
        "unknown-constraint, 500, INTERNAL_ERROR,          /platform/internal-error",
        "no-tenant,          500, TENANT_CONTEXT_MISSING,  /platform/tenant-context-missing",
        "optimistic,         412, VERSION_CONFLICT,        /platform/version-conflict",
        "circuit,            503, DEPENDENCY_UNAVAILABLE,  /platform/dependency-unavailable"
    })
    void shouldRenderEveryCategoryAsProblemDetails(String kind, int status, String code, String typeSuffix) {
        assertThatProblem(fail(kind))
                .hasStatus(status)
                .hasCode(code)
                .hasTypeEndingWith(typeSuffix)
                .hasStandardMembers()
                .hasNoInternals();
    }

    @Test
    void shouldIncludeLocalizedTitleDetailTemplateAndSafeDetails() {
        assertThatProblem(fail("not-found"))
                .hasTitle("Thing not found")
                .hasDetailContaining("Thing " + DummyController.THING_ID + " does not exist.")
                .hasDetail("thingId", DummyController.THING_ID.toString());
    }

    @Test
    void shouldResolveTitleFromAcceptLanguage() {
        MvcTestResult result = mvc.get()
                .uri("/test/errors/not-found")
                .header("Accept-Language", "si")
                .exchange();

        assertThatProblem(result).hasTitle("[si] Thing not found");
    }

    @Test
    void shouldHideDetailsOfInternalErrors() {
        var problem = assertThatProblem(fail("internal"))
                .hasDetailContaining("Quote requestId")
                .body();

        assertThat(problem.has("details")).isFalse();
        assertThat(problem.toString()).doesNotContain("s3cr3t");
    }

    @Test
    void shouldSendRetryAfterOnDependencyFailure() {
        assertThat(fail("dependency")).hasHeader("Retry-After", "5");
        assertThat(fail("circuit")).hasHeader("Retry-After", "5");
    }

    @Test
    void shouldCarryCurrentVersionOnVersionConflict() {
        assertThatProblem(fail("precondition")).hasDetail("currentVersion", "5");
    }

    @Test
    void shouldListFieldErrorsOfValidationFailure() {
        assertThatProblem(fail("fields")).hasFieldError("name", "REQUIRED");
    }

    @Test
    void shouldReportBeanValidationErrorsAs422() {
        MvcTestResult result = mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", "0193a6f1-0000-7000-8000-000000000101")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"\", \"price\": null}")
                .exchange();

        assertThatProblem(result)
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED")
                .hasFieldError("name", "NOT_BLANK")
                .hasFieldError("price", "NOT_NULL");
    }

    @Test
    void shouldRejectMalformedJsonAs400() {
        MvcTestResult result = mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", "0193a6f1-0000-7000-8000-000000000102")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": ")
                .exchange();

        assertThatProblem(result).hasStatus(400).hasCode("MALFORMED_REQUEST").hasNoInternals();
    }

    @Test
    void shouldRejectUnknownPropertiesAs400() {
        MvcTestResult result = mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", "0193a6f1-0000-7000-8000-000000000103")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Eggs\", \"price\": {\"amount\": \"1.00\", \"currency\": \"LKR\"}, \"nmae\": 1}")
                .exchange();

        assertThatProblem(result)
                .hasStatus(400)
                .hasCode("MALFORMED_REQUEST")
                .hasFieldError("nmae", "UNKNOWN_PROPERTY")
                .hasNoInternals();
    }

    @Test
    void shouldRejectMoneyAmountsSentAsNumbers() {
        MvcTestResult result = mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", "0193a6f1-0000-7000-8000-000000000104")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Eggs\", \"price\": {\"amount\": 1.10, \"currency\": \"LKR\"}}")
                .exchange();

        assertThatProblem(result).hasStatus(400).hasCode("MALFORMED_REQUEST");
    }

    @Test
    void shouldRejectAParameterBreakingItsConstraintAs400() {
        assertThatProblem(mvc.get().uri("/test/validated?period=2026-13").exchange())
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER")
                .hasFieldError("period", "PATTERN")
                .hasNoInternals();
        assertThat(mvc.get().uri("/test/validated?period=2026-11").exchange()).hasStatusOk();
    }

    @Test
    void shouldRejectInvalidPathUuidAs400() {
        assertThatProblem(mvc.get().uri("/test/things/not-a-uuid").exchange())
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void shouldReturn404ForUnknownRoutes() {
        assertThatProblem(mvc.get().uri("/test/nowhere").exchange())
                .hasStatus(404)
                .hasCode("RESOURCE_NOT_FOUND");
    }

    @Test
    void shouldReturn405WithAllowHeader() {
        MvcTestResult result = mvc.delete().uri("/test/things").exchange();

        assertThatProblem(result).hasStatus(405).hasCode("METHOD_NOT_ALLOWED");
        assertThat(result).headers().containsHeader("Allow");
    }

    @Test
    void shouldReturn415ForUnsupportedMediaType() {
        MvcTestResult result = mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", "0193a6f1-0000-7000-8000-000000000105")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello")
                .exchange();

        assertThatProblem(result).hasStatus(415).hasCode("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void shouldReturn413WhenBodyExceedsLimit() {
        MvcTestResult result = mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", "0193a6f1-0000-7000-8000-000000000106")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + "x".repeat(5000) + "\"}")
                .exchange();

        assertThatProblem(result).hasStatus(413).hasCode("PAYLOAD_TOO_LARGE").hasDetail("maxBytes", "4096");
    }

    @Test
    void shouldValidatePageLimit() {
        assertThatProblem(mvc.get().uri("/test/things?limit=201").exchange())
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
        assertThatProblem(mvc.get().uri("/test/things?limit=10&cursor=@@@").exchange())
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void shouldPaginateWithCursorEnvelope() {
        assertThat(mvc.get().uri("/test/things?limit=2").exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.items", items -> assertThat(items).asArray().hasSize(2))
                .hasPathSatisfying("$.page.hasMore", more -> assertThat(more).isEqualTo(true))
                .hasPathSatisfying(
                        "$.page.nextCursor", cursor -> assertThat(cursor).isNotNull());
    }

    @Test
    void shouldSerializeMoneyAsDecimalString() {
        assertThat(mvc.get().uri("/test/things/{id}", DummyController.THING_ID).exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.price.amount", amount -> assertThat(amount).isEqualTo("62.50"))
                .hasPathSatisfying(
                        "$.price.currency", currency -> assertThat(currency).isEqualTo("LKR"))
                .hasPathSatisfying("$.id", id -> assertThat(id).isEqualTo(DummyController.THING_ID.toString()));
    }

    @Test
    void shouldEchoValidRequestIdAndReplaceInvalidOne() {
        String valid = "0193a6f1-0000-7000-8000-0000000000ff";

        MvcTestResult echoed = mvc.get()
                .uri("/test/errors/conflict")
                .header("X-Request-Id", valid)
                .exchange();
        MvcTestResult replaced = mvc.get()
                .uri("/test/errors/conflict")
                .header("X-Request-Id", "<script>")
                .exchange();

        assertThat(echoed).hasHeader("X-Request-Id", valid);
        assertThatProblem(echoed).hasStatus(409);
        assertThat(assertThatProblem(echoed).body().path("requestId").asString())
                .isEqualTo(valid);
        assertThat(replaced.getResponse().getHeader("X-Request-Id"))
                .isNotEqualTo("<script>")
                .hasSize(36);
    }

    @Test
    void shouldWriteOneAccessLogLineWithPathTemplate(CapturedOutput output) {
        mvc.get().uri("/test/things/{id}", DummyController.THING_ID).exchange();

        String accessLines = output.getOut()
                .lines()
                .filter(line -> line.contains("com.bmp.web.access") || line.contains("GET /test/things/{id}"))
                .reduce("", (a, b) -> a + b + "\n");
        assertThat(accessLines).contains("GET /test/things/{id} -> 200");
        assertThat(accessLines).doesNotContain(DummyController.THING_ID.toString());
    }
}
