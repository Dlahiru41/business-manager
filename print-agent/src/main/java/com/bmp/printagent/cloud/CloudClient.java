package com.bmp.printagent.cloud;

import com.bmp.printagent.config.AgentProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The documents API as a print agent uses it (documents.yaml), with the device credential: an access token from the
 * identity provider's token endpoint (client credentials), kept until shortly before it expires.
 */
public class CloudClient {

    private static final Logger log = LoggerFactory.getLogger(CloudClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TOKEN_MARGIN = Duration.ofSeconds(30);
    private static final int OK = 200;
    private static final int NO_CONTENT = 204;
    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int SUCCESS_END = 300;

    private final URI baseUrl;
    private final URI tokenUrl;
    private final UUID agentId;
    private final String clientId;
    private final String clientSecret;
    private final int waitSeconds;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http;
    private volatile @Nullable Token token;

    private record Token(String value, Instant expiresAt) {}

    public CloudClient(AgentProperties.Cloud settings, JsonMapper json, Clock clock) {
        this.baseUrl = required(settings.baseUrl(), "agent.cloud.base-url");
        this.tokenUrl = required(settings.tokenUrl(), "agent.cloud.token-url");
        this.agentId = required(settings.agentId(), "agent.cloud.agent-id");
        this.clientId = required(settings.clientId(), "agent.cloud.client-id");
        this.clientSecret = required(settings.clientSecret(), "agent.cloud.client-secret");
        this.waitSeconds = settings.waitSeconds();
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** {@code GET /api/v1/print-agents/{id}/template} (DOC-06). */
    public JsonNode template() throws CloudUnavailableException {
        HttpResponse<String> response = send(get("/api/v1/print-agents/" + agentId + "/template", CALL_TIMEOUT));
        if (response.statusCode() != OK) {
            throw unexpected("template", response);
        }
        return body(response);
    }

    /** {@code GET /api/v1/print-agents/{id}/jobs:next}: the next job, or empty when none arrived (DOC-05). */
    public Optional<JsonNode> nextJob() throws CloudUnavailableException {
        HttpResponse<String> response = send(get(
                "/api/v1/print-agents/" + agentId + "/jobs:next?waitSeconds=" + waitSeconds,
                CALL_TIMEOUT.plusSeconds(waitSeconds)));
        return switch (response.statusCode()) {
            case OK -> Optional.of(body(response));
            case NO_CONTENT -> Optional.empty();
            default -> throw unexpected("next job", response);
        };
    }

    /**
     * {@code POST /api/v1/print-jobs/{id}/result}. The idempotency key is derived from the job, so a result sent again
     * after a lost answer is replayed, not refused. A job the cloud no longer knows, or that already has its result,
     * needs no answer: both count as reported.
     */
    public void report(UUID jobId, boolean printed, @Nullable String error, @Nullable String printerStatus)
            throws CloudUnavailableException {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", printed ? "PRINTED" : "FAILED");
        if (!printed && error != null) {
            result.put("error", error);
        }
        if (printerStatus != null) {
            result.put("printerStatus", printerStatus);
        }
        HttpRequest request = authorized("/api/v1/print-jobs/" + jobId + "/result", CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(
                        "Idempotency-Key",
                        UUID.nameUUIDFromBytes(("print-job-result:" + jobId).getBytes(StandardCharsets.UTF_8))
                                .toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(result)))
                .build();
        HttpResponse<String> response = send(request);
        int status = response.statusCode();
        if (status == NOT_FOUND || status == CONFLICT) {
            log.atWarn()
                    .addKeyValue("printJobId", jobId)
                    .addKeyValue("httpStatus", status)
                    .log("Print job result not taken; the cloud has none to take");
        } else if (status < OK || status >= SUCCESS_END) {
            throw unexpected("result", response);
        }
    }

    private HttpRequest get(String path, Duration timeout) throws CloudUnavailableException {
        return authorized(path, timeout).GET().build();
    }

    private HttpRequest.Builder authorized(String path, Duration timeout) throws CloudUnavailableException {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken());
    }

    private String accessToken() throws CloudUnavailableException {
        Token current = token;
        if (current != null && clock.instant().isBefore(current.expiresAt())) {
            return current.value();
        }
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(HttpRequest.newBuilder(tokenUrl)
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
        if (response.statusCode() != OK) {
            throw new CloudUnavailableException(
                    "The identity provider refused the device credential (HTTP " + response.statusCode() + ")", null);
        }
        JsonNode body = body(response);
        String value = body.path("access_token").asString("");
        if (value.isEmpty()) {
            throw new CloudUnavailableException("The identity provider sent no access token", null);
        }
        Duration life = Duration.ofSeconds(body.path("expires_in").asLong(60));
        Token fresh = new Token(value, clock.instant().plus(life.minus(TOKEN_MARGIN)));
        token = fresh;
        return fresh.value();
    }

    private HttpResponse<String> send(HttpRequest request) throws CloudUnavailableException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == UNAUTHORIZED) {
                token = null;
                throw new CloudUnavailableException("The cloud refused the access token", null);
            }
            return response;
        } catch (IOException e) {
            throw new CloudUnavailableException(
                    "The cloud is unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudUnavailableException("Interrupted", e);
        }
    }

    private JsonNode body(HttpResponse<String> response) throws CloudUnavailableException {
        try {
            return json.readTree(response.body());
        } catch (JacksonException e) {
            throw new CloudUnavailableException("The cloud sent a body that is not JSON", e);
        }
    }

    private static CloudUnavailableException unexpected(String what, HttpResponse<String> response) {
        return new CloudUnavailableException(
                "The cloud answered HTTP " + response.statusCode() + " for the " + what, null);
    }

    private static <T> T required(@Nullable T value, String property) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalStateException(property + " must be set when agent.cloud.enabled is true");
        }
        return Objects.requireNonNull(value);
    }
}
