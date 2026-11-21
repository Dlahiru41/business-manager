package com.bmp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.security.TestJwtConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** T00-05 acceptance: health on the management port, structured logs with correlation ids, masking, metrics. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithPostgres
@Import(TestJwtConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    private HttpResponse<String> get(int targetPort, String path, String requestId)
            throws IOException, InterruptedException {
        return get(targetPort, path, requestId, null);
    }

    private HttpResponse<String> get(int targetPort, String path, String requestId, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .header("X-Request-Id", requestId)
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndProbesShouldBeUpOnTheManagementPort() throws Exception {
        String id = UUID.randomUUID().toString();

        assertThat(get(managementPort, "/actuator/health", id).body()).contains("\"status\":\"UP\"");
        assertThat(get(managementPort, "/actuator/health/liveness", id).statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness", id).statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/prometheus", id).statusCode())
                .as("metrics need a platform administrator")
                .isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", id, JwtTestTokens.platformAdmin())
                        .body())
                .contains("bmp_idempotency_replays_total");
        assertThat(get(port, "/actuator/health", id).statusCode())
                .as("actuator is not exposed on the public port")
                .isNotEqualTo(200);
    }

    @Test
    void requestShouldProduceOneStructuredAccessLogLineWithCorrelationIds(CapturedOutput output) throws Exception {
        String requestId = UUID.randomUUID().toString();

        HttpResponse<String> response = get(port, "/api/v1/not-a-resource", requestId);

        assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId);
        assertThat(response.body()).contains("\"requestId\":\"" + requestId + "\"");
        var accessLines = output.getOut()
                .lines()
                .filter(line -> line.contains("com.bmp.web.access") && line.contains(requestId))
                .toList();
        assertThat(accessLines).hasSize(1);
        assertThat(accessLines.getFirst())
                .startsWith("{")
                .contains("\"traceId\":\"")
                .contains("\"requestId\":\"" + requestId + "\"")
                .contains("\"http\":{")
                .contains("\"status\":401");
    }

    @Test
    void structuredLogsShouldMaskPersonalData(CapturedOutput output) {
        LoggerFactory.getLogger("com.bmp.test").info("Receipt e-mailed to {}", "kamala@example.lk");

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains("Receipt e-mailed to"))
                .findFirst()
                .orElseThrow();
        assertThat(line).contains("***@***").doesNotContain("kamala@example.lk");
    }
}
