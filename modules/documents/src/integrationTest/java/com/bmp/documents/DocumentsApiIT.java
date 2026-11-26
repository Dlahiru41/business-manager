package com.bmp.documents;

import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.documents.api.DocumentsQueries;
import com.bmp.documents.api.dto.PrintTemplate;
import com.bmp.documents.application.DocumentsHandlers;
import com.bmp.documents.domain.model.PrintJob;
import com.bmp.shared.context.TenantContext;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.ContractSecurity;
import com.bmp.test.web.OpenApiValidation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Receipts of the pilot shop's bills, reprints through a print agent, and the contract (DOC-01..DOC-06). */
@SpringBootTest(classes = DocumentsTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class DocumentsApiIT {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("documents");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationContext context;

    @Autowired
    DocumentsQueries documents;

    @Autowired
    DocumentsHandlers handlers;

    /** The pilot shop with register R1, its shift and one cash bill of 10 eggs. */
    private record Shop(PilotShop shop, ApiClient client, String owner, String location, String bill) {

        MvcTestResult post(String path, String body) {
            MvcTestResult result = client.post(path, owner, body);
            CONTRACT.assertConforms(result);
            return result;
        }

        MvcTestResult get(String path) {
            MvcTestResult result = client.get(path, owner);
            CONTRACT.assertConforms(result);
            return result;
        }
    }

    private Shop shop(String name) {
        PilotShop shop = PilotShopFixture.create(mvc, name + " " + UUID.randomUUID());
        ApiClient client = new ApiClient(mvc);
        String owner = shop.owner();
        String location = ApiClient.json(client.get("/api/v1/locations", owner))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        String register = ApiClient.json(client.post(
                        "/api/v1/registers",
                        owner,
                        "{\"code\": \"R1\", \"locationId\": \"" + location + "\", \"billPrefix\": \"R1\"}"))
                .path("id")
                .asString();
        assertThat(client.post("/api/v1/shifts", owner, "{\"registerId\": \"" + register + "\"}"))
                .hasStatus(201);
        assertThat(
                        client.post(
                                "/api/v1/receipt-templates",
                                owner,
                                "{\"name\": \"Counter\", \"paperWidthMm\": 58, \"language\": \"en\", \"isDefault\": true,"
                                        + " \"header\": {\"lines\": [\"Lanka Mart\", \"12 Main Street\"]}, \"footer\": "
                                        + "{\"lines\": [\"Thank you\", \"\", 7]}, \"fields\": {\"showQr\": true, \"phone\": \"081 222\"}}"))
                .hasStatus(201);
        JsonNode egg = shop.variant("EGG-RED-L");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long replicas = TenantContext.callWith(
                    shop.tenant(),
                    () -> new TransactionTemplate(transactions)
                            .execute(s -> jdbc.sql("SELECT count(*) FROM sales.sale_item WHERE variant_id::text = :v")
                                    .param("v", egg.path("id").asString())
                                    .query(Long.class)
                                    .single()));
            assertThat(Objects.requireNonNull(replicas)).isEqualTo(1L);
        });
        MvcTestResult bill = client.post(
                "/api/v1/bills",
                owner,
                "{\"id\": \"" + UUID.randomUUID() + "\", \"number\": \"R1-000001\", \"registerId\": \"" + register
                        + "\", \"channel\": \"RETAIL\", \"lines\": [{\"id\": \"" + UUID.randomUUID()
                        + "\", \"variantId\": \"" + egg.path("id").asString() + "\", \"packagingUnitId\": \""
                        + PilotShop.unit(egg, "piece") + "\", \"qty\": \"10\", \"unitPrice\": \"55.00\"}],"
                        + " \"complete\": true, \"tenders\": [{\"id\": \"" + UUID.randomUUID()
                        + "\", \"method\": \"CASH\", \"amount\": \"550.00\", \"tendered\": \"1000.00\"}], \"totals\": "
                        + "{\"subtotal\": \"550.00\", \"discountTotal\": \"0.00\", \"taxTotal\": \"0.00\","
                        + " \"grandTotal\": \"550.00\"}}");
        assertThat(bill).hasStatus(201);
        return new Shop(
                shop, client, owner, location, ApiClient.json(bill).path("id").asString());
    }

    @Test
    void doc01doc02ABillIsRenderedAsTextEscPosAndPdf() throws Exception {
        Shop s = shop("Render");

        MvcTestResult text =
                s.post("/api/v1/documents/receipts:render", "{\"format\": \"TEXT\", \"billId\": \"" + s.bill() + "\"}");
        String preview = text.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(preview)
                .contains("Lanka Mart", "12 Main Street", "Tel: 081 222", "Bill R1-000001", "Register R1")
                .contains("10 Piece x 55.00", "TOTAL LKR", "550.00", "Change", "450.00", "Thank you", "[QR ");
        assertThat(preview.lines().filter(l -> l.startsWith("-")).findFirst().orElseThrow())
                .hasSize(32);
        MvcTestResult escPos = s.post(
                "/api/v1/documents/receipts:render",
                "{\"format\": \"ESCPOS\", \"billId\": \"" + s.bill() + "\", \"paperWidthMm\": 80, \"copy\": true}");
        byte[] bytes = escPos.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, StandardCharsets.US_ASCII)).contains("*** COPY ***");
        assertThat(bytes[bytes.length - 5]).as("drawer kick after cash").isEqualTo((byte) 0x1b);
        MvcTestResult pdf =
                s.post("/api/v1/documents/receipts:render", "{\"format\": \"PDF\", \"billId\": \"" + s.bill() + "\"}");
        try (var document = Loader.loadPDF(pdf.getResponse().getContentAsByteArray())) {
            assertThat(new PDFTextStripper().getText(document)).contains("Lanka Mart", "R1-000001");
        }
        MvcTestResult given = s.post("/api/v1/documents/receipts:render", """
                {"format": "TEXT", "receipt": {"billId": "%s", "billNumber": "R1-000099",
                 "issuedAt": "2026-11-25T14:30:00", "lines": [{"name": "Soya meat", "qty": "2", "unit": "packet",
                 "unitPrice": "120.00", "lineTotal": "240.00"}], "total": "240.00", "currency": "LKR",
                 "tenders": [{"method": "CARD", "amount": "240.00"}]}}""".formatted(UUID.randomUUID()));
        assertThat(given.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("R1-000099", "2026-11-25 14:30", "2 packet x 120.00", "Card");
        assertThatProblem(s.post(
                        "/api/v1/documents/receipts:render",
                        "{\"format\": \"TEXT\", \"billId\": \"" + UUID.randomUUID() + "\"}"))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");
        assertThatProblem(s.post("/api/v1/documents/receipts:render", "{\"format\": \"TEXT\"}"))
                .hasStatus(422);
        assertThatProblem(s.post("/api/v1/documents/receipts:render", """
                        {"format": "TEXT", "receipt": {"billId": "%s", "billNumber": "R1-000098",
                         "issuedAt": "25/11/2026 14:30", "lines": [{"name": "Eggs", "qty": "1", "unit": "piece",
                         "unitPrice": "55.00", "lineTotal": "55.00"}], "total": "55.00", "currency": "LKR"}}""".formatted(UUID.randomUUID())))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
    }

    @Test
    void doc05doc06AReprintIsPrintedByTheLocationsAgentThroughLongPolling() {
        Shop s = shop("Reprint");
        assertThatProblem(s.post("/api/v1/bills/" + s.bill() + "/reprint", "{}"))
                .hasStatus(409)
                .hasCode("NO_PRINT_AGENT");
        String agent = ApiClient.json(s.post(
                        "/api/v1/print-agents",
                        "{\"locationId\": \"" + s.location() + "\", \"name\": \"Counter\", \"paperWidthMm\": 58}"))
                .path("id")
                .asString();
        assertThatProblem(s.post(
                        "/api/v1/print-agents",
                        "{\"locationId\": \"" + UUID.randomUUID() + "\", \"name\": \"Nowhere\", \"paperWidthMm\": 80}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
        assertThat(s.get("/api/v1/print-agents/" + agent + "/jobs:next?waitSeconds=0"))
                .hasStatus(204);

        JsonNode job = ApiClient.json(s.post("/api/v1/bills/" + s.bill() + "/reprint", "{}"));
        assertThat(job.path("status").asString()).isEqualTo("QUEUED");
        assertThat(job.path("receipt").path("copy").asBoolean()).isTrue();
        JsonNode next = ApiClient.json(s.get("/api/v1/print-agents/" + agent + "/jobs:next?waitSeconds=1"));
        assertThat(next.path("id").asString()).isEqualTo(job.path("id").asString());
        assertThat(next.path("status").asString()).isEqualTo("SENT");
        assertThat(next.path("receipt").path("total").asString()).isEqualTo("550.00");
        assertThat(s.get("/api/v1/print-agents/" + agent + "/jobs:next?waitSeconds=1"))
                .as("handed out once")
                .hasStatus(204);
        JsonNode printed = ApiClient.json(s.post(
                "/api/v1/print-jobs/" + next.path("id").asString() + "/result",
                "{\"status\": \"PRINTED\", \"printerStatus\": \"ONLINE\"}"));
        assertThat(printed.path("status").asString()).isEqualTo("PRINTED");
        assertThatProblem(s.post(
                        "/api/v1/print-jobs/" + next.path("id").asString() + "/result",
                        "{\"status\": \"FAILED\", \"error\": \"Late\"}"))
                .hasStatus(409)
                .hasCode("PRINT_JOB_STATE_INVALID");
        assertThatProblem(s.post("/api/v1/print-jobs/" + UUID.randomUUID() + "/result", "{\"status\": \"PRINTED\"}"))
                .hasStatus(404);
        JsonNode template = ApiClient.json(s.get("/api/v1/print-agents/" + agent + "/template"));
        assertThat(template.path("shopName").asString()).isEqualTo("Lanka Mart");
        assertThat(template.path("paperWidthMm").asInt()).isEqualTo(58);
        assertThat(template.path("showQr").asBoolean()).isTrue();
        PrintTemplate forTills = TenantContext.callWith(s.shop().tenant(), () -> documents.receiptTemplate());
        assertThat(forTills.shopName()).isEqualTo("Lanka Mart");
        assertThat(forTills.addressLines()).containsExactly("12 Main Street");
        assertThat(forTills.phone()).isEqualTo("081 222");
        assertThat(forTills.paperWidthMm()).isEqualTo(58);
        assertThat(forTills.version()).isEqualTo(template.path("version").asLong());
        assertThat(ApiClient.json(s.get("/api/v1/print-agents?locationId=" + s.location()))
                        .path("items"))
                .hasSize(1);
        assertThat(ApiClient.json(s.get("/api/v1/print-agents/" + agent))
                        .path("status")
                        .asString())
                .isEqualTo("ONLINE");
        assertThatProblem(s.post(
                        "/api/v1/bills/" + s.bill() + "/reprint", "{\"agentId\": \"" + UUID.randomUUID() + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_PRINT_AGENT");
        assertThatProblem(s.get("/api/v1/print-agents/" + UUID.randomUUID())).hasStatus(404);
        assertThatProblem(s.get("/api/v1/print-agents?cursor=!!")).hasStatus(400);

        String second = ApiClient.json(s.post("/api/v1/bills/" + s.bill() + "/reprint", "{}"))
                .path("id")
                .asString();
        Optional<PrintJob> handed = TenantContext.callWith(
                s.shop().tenant(), () -> handlers.next(UUID.fromString(agent), Duration.ofMinutes(5)));
        assertThat(handed)
                .as("a job waiting is handed out at once, however long the agent would wait")
                .hasValueSatisfying(j -> assertThat(j.id()).hasToString(second));
        JsonNode failed = ApiClient.json(s.post(
                "/api/v1/print-jobs/" + second + "/result", "{\"status\": \"FAILED\", \"error\": \"Paper jam\"}"));
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("error").asString()).isEqualTo("Paper jam");
        assertThat(ApiClient.json(s.get("/api/v1/print-agents/" + agent))
                        .path("status")
                        .asString())
                .as("no printer status reported: the agent answered, so it is online")
                .isEqualTo("ONLINE");
    }

    @Test
    void documentsKeepTheirContractAndTenantsTheirAgents() throws Exception {
        Shop a = shop("Documents A");
        String nobody = JwtTestTokens.builder()
                .tenant(a.shop().tenant())
                .roles("NOBODY")
                .build();
        ContractSecurity.assertEveryOperationIsProtected("documents", mvc, context, nobody);
        RlsCoverage.assertCovered(List.of("documents"), List.of());
        String agent = ApiClient.json(a.post(
                        "/api/v1/print-agents",
                        "{\"locationId\": \"" + a.location() + "\", \"name\": \"Counter\", \"paperWidthMm\": 80}"))
                .path("id")
                .asString();
        String id = UUID.randomUUID().toString();
        String backOffice = "{\"id\": \"" + id + "\", \"locationId\": \"" + a.location()
                + "\", \"name\": \"Back office\", \"paperWidthMm\": 80}";
        assertThat(ApiClient.json(a.post("/api/v1/print-agents", backOffice))
                        .path("id")
                        .asString())
                .isEqualTo(id);
        assertThat(ApiClient.json(a.post("/api/v1/print-agents", backOffice.replace("Back office", "Renamed")))
                        .path("name")
                        .asString())
                .as("an agent registering again under its id is the same agent")
                .isEqualTo("Back office");
        PilotShop other = PilotShopFixture.create(mvc, "Documents B " + UUID.randomUUID());
        MvcTestResult untemplated =
                a.client().post("/api/v1/documents/receipts:render", other.owner(), """
                        {"format": "TEXT", "receipt": {"billId": "%s", "billNumber": "R1-000001",
                         "issuedAt": "2026-11-25T14:30:00", "lines": [{"name": "Eggs", "qty": "1", "unit": "piece",
                         "unitPrice": "55.00", "lineTotal": "55.00"}], "total": "55.00", "currency": "LKR"}}""".formatted(UUID.randomUUID()));
        CONTRACT.assertConforms(untemplated);
        assertThat(untemplated.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("no template yet: the tenant's name heads the receipt")
                .contains("Documents B")
                .doesNotContain("Tel:");

        assertThat(a.client().get("/api/v1/print-agents/" + agent, other.owner()))
                .hasStatus(404);
        assertThat(a.client().post("/api/v1/bills/" + a.bill() + "/reprint", other.owner(), "{}"))
                .hasStatus(404);
    }
}
