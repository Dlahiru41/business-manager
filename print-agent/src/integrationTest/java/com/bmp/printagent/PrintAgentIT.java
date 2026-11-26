package com.bmp.printagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent on the shop PC with a network printer and the cloud, both fakes: the till's receipts print at once or
 * wait on disk for the printer, reprints from the cloud print once, and results reach the cloud when it is back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrintAgentIT {

    private static final String LOCAL_TOKEN = "local-token-for-the-tests";
    private static final UUID AGENT = UUID.randomUUID();
    private static final FakePrinter PRINTER = FakePrinter.start();
    private static final FakeCloud CLOUD = FakeCloud.start();
    private static final Path DATA = temporaryDirectory();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void agent(DynamicPropertyRegistry registry) {
        registry.add("agent.local-token", () -> LOCAL_TOKEN);
        registry.add("agent.data-dir", DATA::toString);
        registry.add("agent.allowed-origins", () -> "http://localhost:5173");
        registry.add("agent.printer.port", PRINTER::port);
        registry.add("agent.printer.timeout", () -> "500ms");
        registry.add("agent.queue.poll-interval", () -> "100ms");
        registry.add("agent.queue.max-backoff", () -> "300ms");
        registry.add("agent.cloud.enabled", () -> true);
        registry.add("agent.cloud.base-url", CLOUD::baseUrl);
        registry.add("agent.cloud.token-url", () -> CLOUD.baseUrl() + "/token");
        registry.add("agent.cloud.agent-id", AGENT::toString);
        registry.add("agent.cloud.client-id", () -> "device-print-agent");
        registry.add("agent.cloud.client-secret", () -> "device-credential-of-the-tests");
        registry.add("agent.cloud.wait-seconds", () -> 1);
        registry.add("agent.cloud.max-backoff", () -> "300ms");
        registry.add("agent.cloud.template-refresh", () -> "200ms");
    }

    @AfterAll
    static void stop() {
        PRINTER.close();
        CLOUD.close();
    }

    @BeforeEach
    void reset() {
        CLOUD.reachable(true);
        CLOUD.template(FakeCloud.FIRST_TEMPLATE);
        PRINTER.on();
        PRINTER.paperOut(false);
        await().atMost(WAIT)
                .until(() -> status().path("queued").asInt() == 0
                        && status().path("templateVersion").asLong(-1) == 0);
        PRINTER.clear();
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("print-agent-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String receipt(String number, boolean cash) {
        return """
                {"billId": "%s", "billNumber": "%s", "issuedAt": "2026-11-25T14:30:00", "register": "R1",
                 "lines": [{"name": "Eggs Red L", "qty": "10", "unit": "piece", "unitPrice": "55.00",
                            "lineTotal": "550.00"}],
                 "total": "550.00", "tenders": [{"method": "CASH", "amount": "550.00"}], "change": "450.00",
                 "currency": "LKR", "cashTendered": %s}""".formatted(UUID.randomUUID(), number, cash);
    }

    private HttpResponse<String> call(String method, String path, String body, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, String body) {
        return call("POST", path, body, LOCAL_TOKEN);
    }

    private JsonNode status() {
        return JSON.readTree(call("GET", "/v1/status", "", LOCAL_TOKEN).body());
    }

    @Test
    void theTillsReceiptPrintsAtOnceWithTheCloudsTemplateAndTheDrawerOpens() {
        assertThat(call("POST", "/v1/print", receipt("R1-000001", true), null).statusCode())
                .as("no token")
                .isEqualTo(401);
        assertThat(call("POST", "/v1/print", receipt("R1-000001", true), "wrong-token-of-sixteen")
                        .statusCode())
                .isEqualTo(401);

        HttpResponse<String> printed = post("/v1/print", receipt("R1-000001", true));

        assertThat(printed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(printed.body()).path("status").asString()).isEqualTo("PRINTED");
        assertThat(PRINTER.documents()).singleElement().satisfies(d -> {
            assertThat(d[0]).as("ESC @ first").isEqualTo((byte) 0x1b);
            assertThat(d[1]).isEqualTo((byte) 0x40);
        });
        assertThat(PRINTER.texts().getFirst()).contains("Lanka Mart", "12 Main Street", "R1-000001", "450.00");
        assertThat(PRINTER.texts().getFirst())
                .as("the drawer opens after cash (DOC-04)")
                .contains(new String(new byte[] {0x1b, 'p', 0, 25, (byte) 250}, StandardCharsets.ISO_8859_1));

        assertThat(post("/v1/drawer/open", "").statusCode()).isEqualTo(204);
        assertThat(PRINTER.documents()).hasSize(2);
        assertThat(PRINTER.documents().get(1)).containsExactly(0x1b, 'p', 0, 25, 250);

        JsonNode status = status();
        assertThat(status.path("printer").asString()).isEqualTo("ONLINE");
        assertThat(status.path("cloud").asString()).isEqualTo("ONLINE");
        assertThat(status.path("lastCloudContactAt").isString()).isTrue();
        PRINTER.paperOut(true);
        assertThat(status().path("printer").asString()).isEqualTo("PAPER_OUT");

        HttpResponse<String> invalid = post("/v1/print", "{\"billNumber\": \"R1-000002\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(invalid.body()).path("code").asString()).isEqualTo("INVALID_RECEIPT");
        assertThat(CLOUD.tokenForms().get("last"))
                .contains("grant_type=client_credentials", "client_id=device-print-agent");
    }

    @Test
    void aReceiptWaitsOnDiskWhileThePrinterIsOffAndComesOutInOrderWhenItIsBack() {
        PRINTER.off();

        HttpResponse<String> first = post("/v1/print", receipt("R1-000010", false));
        HttpResponse<String> second = post("/v1/print", receipt("R1-000011", false));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(JSON.readTree(first.body()).path("status").asString()).isEqualTo("QUEUED");
        assertThat(second.statusCode()).isEqualTo(202);
        JsonNode status = status();
        assertThat(status.path("printer").asString()).isEqualTo("OFFLINE");
        assertThat(status.path("queued").asInt()).isEqualTo(2);
        HttpResponse<String> drawer = post("/v1/drawer/open", "");
        assertThat(drawer.statusCode()).as("never opened later").isEqualTo(503);
        assertThat(JSON.readTree(drawer.body()).path("code").asString()).isEqualTo("PRINTER_OFFLINE");

        PRINTER.on();

        await().atMost(WAIT).until(() -> PRINTER.documents().size() == 2);
        assertThat(PRINTER.texts().get(0)).contains("R1-000010");
        assertThat(PRINTER.texts().get(1)).contains("R1-000011");
        assertThat(status().path("queued").asInt()).isZero();
    }

    @Test
    void reprintsFromTheCloudPrintOnceAndTheirResultsReachTheCloudWhenItIsBack() {
        CLOUD.reachable(false);
        await().atMost(WAIT).until(() -> "OFFLINE".equals(status().path("cloud").asString()));

        assertThat(post("/v1/print", receipt("R1-000020", false)).statusCode())
                .as("the cloud being away changes nothing for the till")
                .isEqualTo(200);

        String jobId = UUID.randomUUID().toString();
        String job = """
                {"id": "%s", "agentId": "%s", "documentType": "RECEIPT", "status": "SENT", "attempts": 1,
                 "templateVersion": 1, "createdAt": "2026-11-25T15:00:00Z", "receipt": %s}""".formatted(
                        jobId,
                        AGENT,
                        receipt("R1-000003", true).replace("\"cashTendered\"", "\"copy\": true, \"cashTendered\""));
        CLOUD.template("""
                {"version": 1, "shopName": "Lanka Mart Kandy", "addressLines": [], "footerLines": [],
                 "paperWidthMm": 58, "openDrawerOnCash": false, "language": "en"}""");
        CLOUD.offer(job);
        CLOUD.loseResults(1);
        CLOUD.reachable(true);

        await().atMost(WAIT)
                .until(() -> CLOUD.results().stream().anyMatch(r -> r.jobId().equals(jobId)));
        assertThat(PRINTER.texts())
                .filteredOn(t -> t.contains("R1-000003"))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t).contains("Lanka Mart Kandy", "COPY");
                    assertThat(t)
                            .as("the new template keeps the drawer shut")
                            .doesNotContain(new String(new byte[] {0x1b, 'p', 0}, StandardCharsets.ISO_8859_1));
                });
        assertThat(CLOUD.results())
                .filteredOn(r -> r.jobId().equals(jobId))
                .singleElement()
                .satisfies(r -> {
                    assertThat(JSON.readTree(r.body()).path("status").asString())
                            .isEqualTo("PRINTED");
                    assertThat(JSON.readTree(r.body()).path("printerStatus").asString())
                            .isEqualTo("ONLINE");
                });
        assertThat(status().path("templateVersion").asLong()).isEqualTo(1);

        CLOUD.offer(job);

        await().atMost(WAIT)
                .until(() -> CLOUD.results().stream()
                                .filter(r -> r.jobId().equals(jobId))
                                .count()
                        == 2);
        assertThat(PRINTER.texts())
                .as("handed out again, printed once")
                .filteredOn(t -> t.contains("R1-000003"))
                .hasSize(1);
        assertThat(CLOUD.results())
                .filteredOn(r -> r.jobId().equals(jobId))
                .extracting(FakeCloud.Result::idempotencyKey)
                .as("the same result is a replay")
                .containsOnly(CLOUD.results().stream()
                        .filter(r -> r.jobId().equals(jobId))
                        .findFirst()
                        .orElseThrow()
                        .idempotencyKey());
    }

    @Test
    void aBrowserTillMayAskFirstFromItsOrigin() {
        HttpResponse<String> preflight;
        try {
            preflight = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/print"))
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization,content-type")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
    }
}
