package com.bmp;

import static com.bmp.test.fixture.PilotShop.json;
import static com.bmp.test.fixture.PilotShop.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import com.bmp.sync.api.events.BillNumberGapDetected;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.fixture.PosSimulator;
import com.bmp.test.fixture.PosSimulator.Line;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.security.TestJwtConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * T03-11: one day of the pilot shop, through the public APIs of the whole platform. A shift and a drawer open, the
 * counter sells online (retail with a weighed line, wholesale with a quantity break and a split tender), the till
 * works offline for twenty bills and uploads them in one batch (twice), one number is missing, a return and a void
 * give money and stock back, and the drawer is counted 100.00 short. At the end stock, sale movements, payments by
 * method and the event registry agree with what happened, each thing exactly once.
 *
 * <p>The identity provider is the one outside system stubbed here: registering the till's device and the manager
 * would otherwise create Keycloak clients and users (the real adapter has its own test in iam).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithPostgres
@Import({TestJwtConfiguration.class, ShopDayFlowIT.Recorder.class})
@ExtendWith(OutputCaptureExtension.class)
class ShopDayFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Recorder recorder;

    @MockitoBean
    IdentityProviderGateway identityProvider;

    private PilotShop shop;
    private String location;
    private String register;

    /** Events other modules publish that this test checks; listeners run in the publishing thread. */
    @TestConfiguration
    static class Recorder {

        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void gap(BillNumberGapDetected event) {
            events.add(event);
        }

        @EventListener
        void reconciled(DrawerReconciled event) {
            events.add(event);
        }

        <T> List<T> of(Class<T> type, PilotShop shop) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .filter(e -> e.toString().contains(shop.tenant().toString()))
                    .toList();
        }
    }

    @BeforeEach
    void setUp() {
        when(identityProvider.registerDevice(any())).thenAnswer(call -> {
            IdentityProviderGateway.NewDeviceClient device = call.getArgument(0);
            return new IdentityProviderGateway.DeviceCredential(device.clientId(), "issued-once-in-the-test");
        });
        when(identityProvider.createUser(any())).thenAnswer(call -> UserId.of(UUID.randomUUID()));
        shop = PilotShopFixture.create(mvc, "Shop day " + UUID.randomUUID());
        location = json(shop.get("/api/v1/locations"))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        List<String> skus = List.of("EGG-RED-L", "OIL", "KAROLA");
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(this.<Long>sql(j -> j.sql("""
                                SELECT (SELECT count(*) FROM inventory.stock_item WHERE variant_id::text IN (:v))
                                     + (SELECT count(*) FROM sales.sale_item WHERE variant_id::text IN (:v))""")
                                .param(
                                        "v",
                                        skus.stream()
                                                .map(s -> shop.variant(s)
                                                        .path("id")
                                                        .asString())
                                                .toList())
                                .query(Long.class)
                                .single()))
                        .as("the products reached inventory and sales")
                        .isEqualTo(6L));
        JsonNode eggs = shop.variant("EGG-RED-L");
        JsonNode oil = shop.variant("OIL");
        JsonNode karola = shop.variant("KAROLA");
        assertThat(shop.post("/api/v1/stock/opening-balances", """
                        {"locationId": "%s", "lines": [
                          {"variantId": "%s", "packagingUnitId": "%s", "qty": "1000", "unitCost": "38.00"},
                          {"variantId": "%s", "packagingUnitId": "%s", "qty": "10", "unitCost": "13800.00"},
                          {"variantId": "%s", "packagingUnitId": "%s", "stockState": "OPEN", "qty": "20",
                           "unitCost": "700.00"},
                          {"variantId": "%s", "packagingUnitId": "%s", "qty": "10", "unitCost": "1800.00"}]}""".formatted(
                                location,
                                eggs.path("id").asString(),
                                unit(eggs, "piece"),
                                oil.path("id").asString(),
                                unit(oil, "can"),
                                oil.path("id").asString(),
                                unit(oil, "bottle-1000"),
                                karola.path("id").asString(),
                                unit(karola, "kg"))))
                .hasStatus(201);
        MvcTestResult settings = shop.get("/api/v1/settings/sales");
        assertThat(shop.patch(
                        "/api/v1/settings/sales",
                        "{\"values\": {\"allowCreditSales\": true}}",
                        PilotShop.etag(settings)))
                .hasStatusOk();
        register = id(shop.post(
                "/api/v1/registers",
                "{\"code\": \"R1\", \"name\": \"Front counter\", \"locationId\": \"" + location
                        + "\", \"billPrefix\": \"R1\"}"));
    }

    private static String id(MvcTestResult result) {
        assertThat(result.getResponse().getStatus()).as("status").isBetween(200, 201);
        return json(result).path("id").asString();
    }

    private <T> T sql(Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                shop.tenant(), () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc))));
    }

    private static String line(PilotShop shop, String sku, String unitCode, String qty, boolean weighed) {
        JsonNode variant = shop.variant(sku);
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"variantId\": \""
                + variant.path("id").asString()
                + "\", \"packagingUnitId\": \"" + unit(variant, unitCode) + "\", \"qty\": \"" + qty + "\""
                + (weighed ? ", \"weighed\": true" : "") + "}";
    }

    /** A bill priced by the platform as a draft, then completed with the tenders its total asks for. */
    private JsonNode sell(
            String number, String channel, String customer, List<String> lines, Function<BigDecimal, String> tenders) {
        MvcTestResult draft = shop.post("/api/v1/bills", """
                {"id": "%s", "number": "%s", "registerId": "%s", "channel": "%s"%s, "lines": [%s]}""".formatted(
                        UUID.randomUUID(),
                        number,
                        register,
                        channel,
                        customer == null ? "" : ", \"customerId\": \"" + customer + "\"",
                        String.join(", ", lines)));
        assertThat(draft).hasStatus(201);
        JsonNode totals = json(draft).path("totals");
        BigDecimal grand =
                new BigDecimal(totals.path("grandTotal").path("amount").asString());
        MvcTestResult completed =
                shop.post("/api/v1/bills/" + json(draft).path("id").asString() + "/complete", """
                {"tenders": [%s], "totals": {"subtotal": "%s", "discountTotal": "%s", "taxTotal": "%s",
                 "grandTotal": "%s"}}""".formatted(
                                tenders.apply(grand),
                                totals.path("subtotal").path("amount").asString(),
                                totals.path("discountTotal").path("amount").asString(),
                                totals.path("taxTotal").path("amount").asString(),
                                grand.toPlainString()));
        assertThat(completed).hasStatusOk();
        return json(completed);
    }

    private JsonNode balance(String sku, String state) {
        JsonNode items = json(shop.get("/api/v1/stock/balances?locationId=" + location + "&variantId="
                        + shop.variant(sku).path("id").asString() + "&state=" + state))
                .path("items");
        assertThat(items).as(sku + " " + state).hasSize(1);
        return items.get(0);
    }

    private MvcTestResult upload(String token, String body) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/sync/uploads")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Device-Time", Instant.now().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatusOk();
        return result;
    }

    /** Notes and coins for an amount, largest first, in the tenant's default denominations. */
    private static String denominations(BigDecimal amount) {
        List<String> counted = new ArrayList<>();
        BigDecimal left = amount;
        for (String value : List.of("5000", "2000", "1000", "500", "100", "50", "20", "10", "5", "2", "1")) {
            BigDecimal note = new BigDecimal(value);
            int count = left.divideToIntegralValue(note).intValueExact();
            if (count > 0) {
                counted.add("{\"value\": \"" + value + "\", \"count\": " + count + "}");
                left = left.subtract(note.multiply(BigDecimal.valueOf(count)));
            }
        }
        assertThat(left).as("whole rupees").isZero();
        return "{\"denominations\": [" + String.join(", ", counted) + "]}";
    }

    private long unfinishedEvents() {
        return jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL"
                        + " AND serialized_event LIKE :tenant")
                .param("tenant", "%" + shop.tenant() + "%")
                .query(Long.class)
                .single();
    }

    @Test
    void oneShopDayEndsWithStockPaymentsAndTheDrawerAsTheyHappened(CapturedOutput output) {
        // Offline bills are dated from here: they are priced as of their time, after the shop's prices.
        Instant dayStart = Instant.now();

        // 1. The shift and the drawer open with a float of 5,000.00.
        String shift = id(shop.post("/api/v1/shifts", "{\"registerId\": \"" + register + "\"}"));
        String drawer = id(shop.post(
                "/api/v1/drawer-sessions", "{\"registerId\": \"" + register + "\", \"openingFloat\": \"5000.00\"}"));

        // 2. Retail: 10 large red eggs, a 750 ml bottle of oil and 350 g of loose karola, weighed; cash with change.
        JsonNode retail = sell(
                "R1-000001",
                "RETAIL",
                null,
                List.of(
                        line(shop, "EGG-RED-L", "piece", "10", false),
                        line(shop, "OIL", "bottle-750", "1", false),
                        line(shop, "KAROLA", "kg", "0.350", true)),
                grand -> "{\"id\": \"%s\", \"method\": \"CASH\", \"amount\": \"%s\", \"tendered\": \"5000.00\"}"
                        .formatted(UUID.randomUUID(), grand.toPlainString()));
        BigDecimal retailTotal = new BigDecimal(
                retail.path("totals").path("grandTotal").path("amount").asString());
        assertThat(retailTotal).as("550 + 550 + 840").isEqualByComparingTo("1940.00");
        assertThat(retail.path("tenders").get(0).path("change").path("amount").asString())
                .isEqualTo("3060.00");

        // 3. Wholesale: 300 large red eggs at the quantity break and two cans of oil; card and cash.
        JsonNode wholesale = sell(
                "R1-000002",
                "WHOLESALE",
                null,
                List.of(line(shop, "EGG-RED-L", "piece", "300", false), line(shop, "OIL", "can", "2", false)),
                grand -> ("{\"id\": \"%s\", \"method\": \"CARD\", \"amount\": \"%s\", \"reference\": \"AUTH-4471\"},"
                                + " {\"id\": \"%s\", \"method\": \"CASH\", \"amount\": \"10000.00\","
                                + " \"tendered\": \"10000.00\"}")
                        .formatted(
                                UUID.randomUUID(),
                                grand.subtract(new BigDecimal("10000.00")).toPlainString(),
                                UUID.randomUUID()));
        JsonNode wholesaleEggs = wholesale.path("lines").get(0);
        assertThat(wholesaleEggs.path("unitPrice").path("amount").asString()).isEqualTo("50.00");
        assertThat(wholesaleEggs.path("ruleDiscount").path("amount").asString())
                .as("5 % off from 300 eggs")
                .isEqualTo("750.00");
        assertThat(wholesaleEggs.path("lineTotal").path("amount").asString()).isEqualTo("14250.00");
        BigDecimal wholesaleTotal = new BigDecimal(
                wholesale.path("totals").path("grandTotal").path("amount").asString());
        assertThat(wholesaleTotal).as("14,250 + 2 × 14,500").isEqualByComparingTo("43250.00");

        // 4. Offline: the till keeps 20 bills, one on credit and one with a discount above the cashier's limit.
        MvcTestResult registered = shop.post(
                "/api/v1/devices",
                "{\"name\": \"Till 1\", \"locationId\": \"" + location + "\", \"registerId\": \"" + register + "\"}");
        assertThat(registered).hasStatus(201);
        String device = json(registered).path("device").path("id").asString();
        String till = JwtTestTokens.builder()
                .tenant(shop.tenant())
                .device(DeviceId.of(UUID.fromString(device)))
                .roles("DEVICE")
                .build();
        MvcTestResult customer = shop.post(
                "/api/v1/parties",
                "{\"type\": \"CUSTOMER\", \"displayName\": \"Hotel Lake View\", \"phone\": \"077 123 4567\"}");
        String hotel = id(customer);
        assertThat(shop.put(
                        "/api/v1/parties/" + hotel + "/credit-policy",
                        "{\"creditAllowed\": true, \"creditLimit\": \"50000.00\", \"paymentTermsDays\": 30}",
                        PilotShop.etag(customer)))
                .hasStatusOk();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(this.<Boolean>sql(
                                j -> j.sql("SELECT coalesce(bool_or(credit_allowed), false) FROM sales.customer_ref"
                                                + " WHERE party_id::text = :p")
                                        .param("p", hotel)
                                        .query(Boolean.class)
                                        .single()))
                        .isTrue());
        PosSimulator pos = new PosSimulator(shop, "R1", 3, UUID.fromString(shift));
        List<PosSimulator.Bill> offlineBills = new ArrayList<>();
        String missing = null;
        for (int i = 0; i < 20; i++) {
            if (i == 10) {
                missing = pos.skip();
            }
            Instant at = dayStart.plusMillis(i * 100L); // priced as of their time: after the shop's prices
            offlineBills.add(
                    switch (i) {
                        case 4 -> pos.credit(at, UUID.fromString(hotel), Line.of("EGG-RED-L", "piece", "20", "55.00"));
                        case 7 -> pos.cash(at, new Line("EGG-RED-L", "piece", "10", "55.00", "110.00"));
                        default -> pos.cash(at, Line.of("EGG-RED-L", "piece", "2", "55.00"));
                    });
        }
        JsonNode uploaded = json(upload(till, pos.batch()));
        assertThat(uploaded.path("accepted").asInt()).isEqualTo(20);
        assertThat(uploaded.path("rejected").asInt()).isZero();
        JsonNode again = json(upload(till, pos.batch()));
        assertThat(again.path("duplicates").asInt()).as("the same batch again").isEqualTo(20);
        assertThat(again.path("accepted").asInt()).isZero();
        for (int i = 0; i < offlineBills.size(); i++) {
            JsonNode flags =
                    json(shop.get("/api/v1/bills/" + offlineBills.get(i).id())).path("flags");
            if (i == 7) {
                assertThat(flags.toString())
                        .as("20 % off without an approver: accepted and flagged")
                        .contains("DISCOUNT_LIMIT_EXCEEDED");
            } else {
                assertThat(flags)
                        .as("bill %d: in its shift, at its price, on credit within the limit", i)
                        .isEmpty();
            }
        }

        // Logging (05 §4): one INFO "Bill completed" per bill, the replay included, and nothing about the customer.
        PosSimulator.Bill credit = offlineBills.get(4);
        assertThat(output.getOut()
                        .lines()
                        .filter(l -> l.contains("\"message\":\"Bill completed\"")
                                && l.contains(credit.id().toString()))
                        .toList())
                .singleElement()
                .satisfies(l -> assertThat(l)
                        .contains("\"level\":\"INFO\"", credit.number())
                        .doesNotContain("Hotel Lake View", "4567", hotel));

        // 5. The skipped number is reported once.
        String gap = Objects.requireNonNull(missing);
        await().atMost(WAIT)
                .until(() -> !recorder.of(BillNumberGapDetected.class, shop).isEmpty());
        assertThat(recorder.of(BillNumberGapDetected.class, shop))
                .singleElement()
                .satisfies(e -> assertThat(e.missingNumbers()).containsExactly(gap));

        // 6. Two eggs of the wholesale bill come back to stock; the refund is cash.
        assertThat(shop.post("/api/v1/bills/" + wholesale.path("id").asString() + "/returns", """
                        {"id": "%s", "reason": "Cracked", "lines": [{"billLineId": "%s", "qty": "2", "restock": true}],
                         "refundTenders": [{"method": "CASH", "amount": "95.00"}]}""".formatted(
                                UUID.randomUUID(), wholesaleEggs.path("id").asString())))
                .hasStatus(201);

        // 7. A manager approves the void of an offline bill of the same day.
        String manager = id(shop.post(
                "/api/v1/users", """
                {"username": "manager.%s", "displayName": "Day manager", "roles": [{"code": "MANAGER"}]}""".formatted(UUID.randomUUID().toString().substring(0, 8))));
        PosSimulator.Bill wrong = offlineBills.get(15);
        assertThat(shop.post(
                        "/api/v1/bills/" + wrong.id() + "/void",
                        "{\"reason\": \"Rang up twice\", \"approvedBy\": \"" + manager + "\"}"))
                .hasStatusOk();

        // 8. The drawer is counted 100.00 short and reconciled.
        await().atMost(WAIT).until(() -> unfinishedEvents() == 0);
        BigDecimal cashSales = retailTotal
                .add(new BigDecimal("10000.00"))
                .add(offlineBills.stream()
                        .filter(b -> b.method().equals("CASH"))
                        .map(PosSimulator.Bill::total)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal expected = new BigDecimal("5000.00")
                .add(cashSales)
                .subtract(new BigDecimal("95.00"))
                .subtract(wrong.total());
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(
                                new BigDecimal(json(shop.get("/api/v1/drawer-sessions/" + drawer + "/expected"))
                                        .path("expected")
                                        .path("amount")
                                        .asString()))
                        .isEqualByComparingTo(expected));
        assertThat(shop.post(
                        "/api/v1/drawer-sessions/" + drawer + "/count",
                        denominations(expected.subtract(new BigDecimal("100.00")))))
                .hasStatusOk();
        assertThat(shop.post(
                        "/api/v1/drawer-sessions/" + drawer + "/reconcile",
                        "{\"note\": \"100 short, cashier informed\"}"))
                .hasStatusOk();
        assertThat(recorder.of(DrawerReconciled.class, shop)).singleElement().satisfies(e -> {
            assertThat(e.expected()).isEqualByComparingTo(expected);
            assertThat(e.variance()).isEqualByComparingTo("-100.00");
        });

        // 9. Stock, sale movements, payments by method, and every event processed once.
        await().atMost(WAIT).until(() -> unfinishedEvents() == 0);
        int offlineEggs = 18 * 2 + 20 + 10;
        assertThat(new BigDecimal(balance("EGG-RED-L", "DEFAULT").path("onHand").asString()))
                .as("1,000 − 10 − 300 − %d offline + 2 returned + 2 voided", offlineEggs)
                .isEqualByComparingTo(BigDecimal.valueOf(1000 - 10 - 300 - offlineEggs + 2 + 2));
        assertThat(new BigDecimal(balance("OIL", "SEALED").path("onHand").asString()))
                .as("eight cans of 19,565.28 ml")
                .isEqualByComparingTo("156522.24");
        assertThat(new BigDecimal(balance("OIL", "OPEN").path("onHand").asString()))
                .as("20 litres less a 750 ml bottle")
                .isEqualByComparingTo("19250");
        assertThat(new BigDecimal(balance("KAROLA", "DEFAULT").path("onHand").asString()))
                .as("10 kg less 350 g, in grams")
                .isEqualByComparingTo("9650");
        Map<String, Object> movements = sql(j -> j.sql("""
                        SELECT (SELECT count(*) FROM sales.bill_line) AS lines,
                               (SELECT count(*) FROM sales.bill_line l WHERE NOT EXISTS (
                                    SELECT 1 FROM inventory.stock_movement m
                                     WHERE m.source_line_id = l.id AND m.type = 'SALE' AND m.cost_amount <> 0)) AS uncosted,
                               (SELECT count(*) FROM inventory.stock_movement WHERE type = 'SALE') AS sales,
                               (SELECT count(*) FROM inventory.stock_movement WHERE type = 'SALE_RETURN') AS returns,
                               (SELECT count(*) FROM inventory.stock_movement WHERE type = 'SALE_VOID') AS voids""").query().singleRow());
        assertThat(movements)
                .as(
                        "3 retail lines, 2 wholesale lines, 20 offline bills of one line; each line costed on its SALE movement (SAL-11)")
                .containsEntry("lines", 25L)
                .containsEntry("uncosted", 0L)
                .containsEntry("sales", 25L)
                .containsEntry("returns", 1L)
                .containsEntry("voids", 1L);
        LocalDate today = LocalDate.now(COLOMBO);
        JsonNode summary = json(shop.get("/api/v1/payments/summary?date=" + today));
        Map<String, JsonNode> byMethod = new HashMap<>();
        summary.path("byMethod").forEach(m -> byMethod.put(m.path("method").asString(), m));
        assertThat(byMethod).containsOnlyKeys("CASH", "CARD");
        assertThat(new BigDecimal(
                        byMethod.get("CASH").path("captured").path("amount").asString()))
                .isEqualByComparingTo(cashSales);
        assertThat(new BigDecimal(
                        byMethod.get("CASH").path("refunded").path("amount").asString()))
                .isEqualByComparingTo(new BigDecimal("95.00").add(wrong.total()));
        assertThat(new BigDecimal(
                        byMethod.get("CARD").path("captured").path("amount").asString()))
                .isEqualByComparingTo(wholesaleTotal.subtract(new BigDecimal("10000.00")));
        assertThat(byMethod.get("CASH").path("count").asInt())
                .as("one payment per cash tender: retail, wholesale and 19 offline")
                .isEqualTo(21);
        assertThat(unfinishedEvents()).as("every event processed").isZero();
        assertThat(this.<Long>sql(j -> j.sql("""
                        SELECT count(*) FROM (SELECT source_line_id FROM inventory.stock_movement
                         WHERE type = 'SALE' GROUP BY source_line_id HAVING count(*) > 1) twice""").query(Long.class).single()))
                .as("no sale booked twice")
                .isZero();
    }
}
