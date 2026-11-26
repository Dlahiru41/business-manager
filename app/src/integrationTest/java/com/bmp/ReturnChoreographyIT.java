package com.bmp;

import static com.bmp.test.fixture.PilotShop.json;
import static com.bmp.test.fixture.PilotShop.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.ReturnedLine;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleLine;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleTender;
import com.bmp.sales.api.events.SaleTotals;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.TestJwtConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * T03-07: returns and voids are choreographed. Sales publishes them; payments gives the money back and inventory puts
 * the stock back, each exactly once, even when a listener fails first and is retried, and when an event is delivered
 * twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithPostgres
@Import(TestJwtConfiguration.class)
class ReturnChoreographyIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    FailedEventPublications failed;

    private PilotShop shop;
    private String location;
    private String register;
    private String drawer;
    private JsonNode eggs;
    private int number;

    @BeforeEach
    void setUp() {
        shop = PilotShopFixture.create(mvc, "Return choreography " + UUID.randomUUID());
        location = json(shop.get("/api/v1/locations"))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        eggs = shop.variant("EGG-RED-L");
        String egg = eggs.path("id").asString();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(this.<Long>inTenant(() ->
                                jdbc.sql("""
                                SELECT (SELECT count(*) FROM inventory.stock_item WHERE variant_id::text = :v)
                                     + (SELECT count(*) FROM sales.sale_item WHERE variant_id::text = :v)""").param("v", egg).query(Long.class).single()))
                        .as("the eggs reached inventory and sales")
                        .isEqualTo(2L));
        assertThat(shop.post("/api/v1/stock/opening-balances", """
                        {"locationId": "%s", "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "100",
                         "unitCost": "38.00"}]}""".formatted(location, egg, unit(eggs, "piece"))))
                .hasStatus(201);
        register = id(shop.post(
                "/api/v1/registers",
                "{\"code\": \"R1\", \"locationId\": \"" + location + "\", \"billPrefix\": \"R1\"}"));
        id(shop.post("/api/v1/shifts", "{\"registerId\": \"" + register + "\"}"));
        drawer = id(shop.post(
                "/api/v1/drawer-sessions", "{\"registerId\": \"" + register + "\", \"openingFloat\": \"1000.00\"}"));
    }

    private static String id(MvcTestResult result) {
        assertThat(result.getResponse().getStatus()).isBetween(200, 201);
        return json(result).path("id").asString();
    }

    private <T> T inTenant(Supplier<T> work) {
        return Objects.requireNonNull(TenantContext.callWith(
                shop.tenant(), () -> new TransactionTemplate(transactions).execute(s -> work.get())));
    }

    private JsonNode bill(String qty) {
        String total = new BigDecimal(qty)
                .multiply(new BigDecimal("55.00"))
                .setScale(2)
                .toPlainString();
        MvcTestResult created = shop.post("/api/v1/bills", """
                {"id": "%s", "number": "R1-%06d", "registerId": "%s", "channel": "RETAIL",
                 "lines": [{"id": "%s", "variantId": "%s", "packagingUnitId": "%s", "qty": "%s", "unitPrice": "55.00"}],
                 "complete": true, "tenders": [{"id": "%s", "method": "CASH", "amount": "%s", "tendered": "%s"}],
                 "totals": {"subtotal": "%s", "discountTotal": "0.00", "taxTotal": "0.00", "grandTotal": "%s"}}""".formatted(
                        UUID.randomUUID(),
                        ++number,
                        register,
                        UUID.randomUUID(),
                        eggs.path("id").asString(),
                        unit(eggs, "piece"),
                        qty,
                        UUID.randomUUID(),
                        total,
                        total,
                        total,
                        total));
        assertThat(created).hasStatus(201);
        return json(created);
    }

    /** Stock movements and refunds booked for one bill line, by type. */
    private Map<String, Object> booked(String billLineId) {
        return inTenant(() -> jdbc.sql("""
                        SELECT (SELECT count(*) FROM inventory.stock_movement WHERE source_line_id::text = :l
                                   AND type = 'SALE') AS sold,
                               (SELECT count(*) FROM inventory.stock_movement WHERE source_line_id::text = :l
                                   AND type IN ('SALE_RETURN', 'SALE_VOID')) AS back,
                               (SELECT coalesce(sum(cost_amount), 0) FROM inventory.stock_movement
                                 WHERE source_line_id::text = :l AND type IN ('SALE_RETURN', 'SALE_VOID')) AS back_cost
                        """).param("l", billLineId).query().singleRow());
    }

    private BigDecimal refunded(String billId) {
        return inTenant(
                () -> jdbc.sql("""
                        SELECT coalesce(sum(r.amount), 0) FROM payments.refund r
                          JOIN payments.payment p ON p.tenant_id = r.tenant_id AND p.id = r.payment_id
                         WHERE p.bill_id::text = :b""").param("b", billId).query(BigDecimal.class).single());
    }

    private String expectedCash() {
        return json(shop.get("/api/v1/drawer-sessions/" + drawer + "/expected"))
                .path("expected")
                .path("amount")
                .asString();
    }

    private long pending(UUID id) {
        return jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL"
                        + " AND serialized_event LIKE :id")
                .param("id", "%" + id + "%")
                .query(Long.class)
                .single();
    }

    @Test
    void aReturnAndAVoidEndInMoneyAndStockBackOnce() {
        JsonNode sold = bill("10");
        String line = sold.path("lines").get(0).path("id").asString();
        await().atMost(WAIT).untilAsserted(() -> assertThat(expectedCash()).isEqualTo("1550.00"));

        assertThat(shop.post(
                        "/api/v1/bills/" + sold.path("id").asString() + "/returns",
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \"" + line
                                + "\", \"qty\": \"2\", \"restock\": true}]}"))
                .hasStatus(201);
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(refunded(sold.path("id").asString())).isEqualByComparingTo("110.00");
            assertThat(booked(line)).containsEntry("sold", 1L).containsEntry("back", 1L);
        });
        assertThat((BigDecimal) booked(line).get("back_cost"))
                .as("two eggs at 38.00")
                .isEqualByComparingTo("76.00");

        MvcTestResult settings = shop.get("/api/v1/settings/sales");
        assertThat(shop.patch(
                        "/api/v1/settings/sales",
                        "{\"values\": {\"voidRequiresManager\": false}}",
                        PilotShop.etag(settings)))
                .hasStatusOk();
        JsonNode wrong = bill("2");
        String wrongLine = wrong.path("lines").get(0).path("id").asString();
        await().atMost(WAIT).untilAsserted(() -> assertThat(booked(wrongLine)).containsEntry("sold", 1L));
        await().atMost(WAIT).untilAsserted(() -> assertThat(expectedCash()).isEqualTo("1550.00"));
        assertThat(shop.post("/api/v1/bills/" + wrong.path("id").asString() + "/void", "{\"reason\": \"Wrong bill\"}"))
                .hasStatusOk();

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(refunded(wrong.path("id").asString())).isEqualByComparingTo("110.00");
            assertThat(booked(wrongLine)).containsEntry("back", 1L);
            assertThat(expectedCash()).as("1,000 + 550 − 110 + 110 − 110").isEqualTo("1440.00");
            assertThat(pending(UUID.fromString(sold.path("id").asString()))
                            + pending(UUID.fromString(wrong.path("id").asString())))
                    .as("every listener finished")
                    .isZero();
        });
    }

    @Test
    void aListenerThatFailsIsRetriedAndADuplicateDeliveryChangesNothing() {
        UUID billId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        Instant at = Instant.now();
        BigDecimal five = new BigDecimal("5");
        SaleCompleted sale = new SaleCompleted(
                UUID.randomUUID(),
                shop.tenant(),
                at,
                billId,
                1,
                EventMetadata.empty(),
                billId,
                "R1-000900",
                "RETAIL",
                null,
                LocationId.of(UUID.fromString(location)),
                UUID.fromString(register),
                null,
                null,
                null,
                null,
                null,
                LocalDate.now(java.time.ZoneId.of("Asia/Colombo")),
                at,
                "LKR",
                true,
                List.of(),
                List.of(new SaleLine(
                        lineId,
                        VariantId.of(UUID.fromString(eggs.path("id").asString())),
                        PackagingUnitId.of(UUID.fromString(unit(eggs, "piece"))),
                        "Eggs",
                        five,
                        five,
                        "DEFAULT",
                        false,
                        new BigDecimal("55.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("275.00"))),
                new SaleTotals(new BigDecimal("275.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("275.00")),
                List.of(new SaleTender(UUID.randomUUID(), "CASH", new BigDecimal("275.00"), null, null, null)));
        UUID note = UUID.randomUUID();
        SaleReturned back = new SaleReturned(
                UUID.randomUUID(),
                shop.tenant(),
                at,
                note,
                1,
                EventMetadata.empty(),
                note,
                "CN-000900",
                billId,
                "R1-000900",
                null,
                LocationId.of(UUID.fromString(location)),
                UUID.fromString(register),
                UUID.fromString(register),
                "LKR",
                "Cracked",
                new BigDecimal("110.00"),
                BigDecimal.ZERO,
                List.of(new ReturnedLine(
                        lineId,
                        sale.lines().getFirst().variantId(),
                        new BigDecimal("2"),
                        new BigDecimal("2"),
                        "DEFAULT",
                        new BigDecimal("110.00"),
                        BigDecimal.ZERO,
                        true)),
                List.of(new RefundTender(refundId, "CASH", new BigDecimal("110.00"), null, null)),
                at);

        publish(back);
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                SELECT count(*) FROM platform.event_publication
                                 WHERE completion_date IS NULL AND status = 'FAILED' AND serialized_event LIKE :note""")
                                .param("note", "%" + note + "%")
                                .query(Long.class)
                                .single())
                        .as("payments and inventory both failed: the sale is not booked yet")
                        .isEqualTo(2L));
        assertThat(refunded(billId.toString())).isZero();

        publish(sale);
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(booked(lineId.toString())).containsEntry("sold", 1L));
        await().atMost(WAIT)
                .untilAsserted(() ->
                        assertThat(pending(billId)).as("the sale is booked").isEqualTo(2L));
        failed.resubmit(ResubmissionOptions.defaults());

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(refunded(billId.toString())).isEqualByComparingTo("110.00");
            assertThat(booked(lineId.toString())).containsEntry("back", 1L);
            assertThat(pending(note)).isZero();
        });
        publish(back);
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(pending(note)).as("delivered again").isZero());
        assertThat(refunded(billId.toString())).as("refunded once").isEqualByComparingTo("110.00");
        assertThat(booked(lineId.toString())).as("restocked once").containsEntry("back", 1L);
    }

    private void publish(Object event) {
        TenantContext.runWith(
                shop.tenant(),
                () -> new TransactionTemplate(transactions).executeWithoutResult(s -> publisher.publishEvent(event)));
    }
}
