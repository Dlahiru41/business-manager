package com.bmp.test.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Validates recorded requests and responses against a module's contract (03-api-standards.md §1, step 5):
 * {@code OpenApiValidation.forModule("tenant").assertConforms(result)}. Contracts are read from
 * {@code contracts/openapi/<module>.yaml} (system property {@code bmp.contracts.dir}, set by the build).
 */
public final class OpenApiValidation {

    private static final Map<String, OpenApiInteractionValidator> VALIDATORS = new ConcurrentHashMap<>();

    private final OpenApiInteractionValidator validator;

    private OpenApiValidation(OpenApiInteractionValidator validator) {
        this.validator = validator;
    }

    public static OpenApiValidation forModule(String module) {
        return new OpenApiValidation(VALIDATORS.computeIfAbsent(module, OpenApiValidation::create));
    }

    /** Fails unless both the request and the response conform to the contract. */
    public void assertConforms(MvcTestResult result) {
        ValidationReport report = validator.validate(request(result.getRequest()), response(result.getResponse()));
        assertThat(report.getMessages())
                .as(
                        "contract violations for %s %s",
                        result.getRequest().getMethod(), result.getRequest().getRequestURI())
                .isEmpty();
    }

    private static OpenApiInteractionValidator create(String module) {
        String contracts = System.getProperty("bmp.contracts.dir", "contracts");
        Path spec = Path.of(contracts, "openapi", module + ".yaml").toAbsolutePath();
        return OpenApiInteractionValidator.createForSpecificationUrl(
                        spec.toUri().toString())
                .build();
    }

    private static Request request(MockHttpServletRequest request) {
        SimpleRequest.Builder builder = new SimpleRequest.Builder(request.getMethod(), request.getRequestURI());
        for (String name : Collections.list(request.getHeaderNames())) {
            builder.withHeader(name, Collections.list(request.getHeaders(name)));
        }
        request.getParameterMap().forEach(builder::withQueryParam);
        byte[] body = request.getContentAsByteArray();
        if (body != null && body.length > 0) {
            builder.withBody(new String(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static com.atlassian.oai.validator.model.Response response(MockHttpServletResponse response) {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.getStatus());
        for (String name : response.getHeaderNames()) {
            builder.withHeader(name, response.getHeaders(name));
        }
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            builder.withBody(body);
        }
        return builder.build();
    }
}
