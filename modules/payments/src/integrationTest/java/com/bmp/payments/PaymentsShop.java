package com.bmp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.OpenApiValidation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The pilot shop with register R1, its open shift and a cashier; bills go through the sales API as a till sends them
 * and payments follow from the sales events. Every payments response is checked against the contract.
 */
final class PaymentsShop {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("payments");

    final PilotShop shop;
    final ApiClient client;
    final TenantId tenant;
    final String owner;
    final UUID cashierId = UUID.randomUUID();
    final String cashier;
    final UUID location;
    final UUID register;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;
    private final AtomicInteger sequence = new AtomicInteger();

    PaymentsShop(MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactions, String name) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.shop = PilotShopFixture.create(mvc, name + " " + UUID.randomUUID());
        this.client = new ApiClient(mvc);
        this.tenant = shop.tenant();
        this.owner = shop.owner();
        this.cashier = cashier(cashierId);
        this.location = UUID.fromString(ApiClient.json(client.get("/api/v1/locations", owner))
                .path("items")
                .get(0)
                .path("id")
                .asString());
        this.register = register("R1");
        assertThat(client.post("/api/v1/shifts", cashier, "{\"registerId\": \"" + register + "\"}"))
                .hasStatus(201);
        List<String> variants = new ArrayList<>();
        for (String sku : List.of("EGG-RED-L", "OIL")) {
            variants.add(shop.variant(sku).path("id").asString());
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(this.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM sales.sale_item WHERE variant_id::text = ANY (:ids)")
                                        .param("ids", variants.toArray(String[]::new))
                                        .query(Long.class)
                                        .single()))
                        .as("the catalog's variants reached sales")
                        .isEqualTo(2L));
    }

    /** A cashier token for {@code user}. */
    String cashier(UUID user) {
        return JwtTestTokens.builder()
                .tenant(tenant)
                .user(UserId.of(user))
                .roles("CASHIER")
                .build();
    }

    UUID register(String code) {
        MvcTestResult created = client.post(
                "/api/v1/registers",
                owner,
                "{\"code\": \"" + code + "\", \"locationId\": \"" + location + "\", \"billPrefix\": \"" + code + "\"}");
        assertThat(created).hasStatus(201);
        return ApiClient.id(created);
    }

    <T> T sql(Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc))));
    }

    String eggs(String qty) {
        JsonNode egg = shop.variant("EGG-RED-L");
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"variantId\": \""
                + egg.path("id").asString()
                + "\", \"packagingUnitId\": \"" + PilotShop.unit(egg, "piece") + "\", \"qty\": \"" + qty
                + "\", \"unitPrice\": \"55.00\"}";
    }

    static String cash(String amount, String tendered) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"method\": \"CASH\", \"amount\": \"" + amount
                + "\", \"tendered\": \"" + tendered + "\"}";
    }

    static String tender(String method, String amount, @Nullable String reference) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"method\": \"" + method + "\", \"amount\": \"" + amount + "\""
                + (reference == null ? "" : ", \"reference\": \"" + reference + "\"") + "}";
    }

    /** A completed bill of {@code qty} large eggs at 55.00, submitted by the cashier. */
    JsonNode bill(String qty, String... tenders) {
        String total = new java.math.BigDecimal(qty)
                .multiply(new java.math.BigDecimal("55.00"))
                .setScale(2)
                .toPlainString();
        MvcTestResult created = client.post(
                "/api/v1/bills",
                cashier,
                "{\"id\": \"" + UUID.randomUUID() + "\", \"number\": \"R1-%06d\"".formatted(sequence.incrementAndGet())
                        + ", \"registerId\": \"" + register + "\", \"channel\": \"RETAIL\", \"lines\": ["
                        + eggs(qty) + "], \"complete\": true, \"tenders\": [" + String.join(", ", tenders)
                        + "], \"totals\": {\"subtotal\": \"" + total + "\", \"discountTotal\": \"0.00\", \"taxTotal\": "
                        + "\"0.00\", \"grandTotal\": \"" + total + "\"}}");
        assertThat(created).hasStatus(201);
        return ApiClient.json(created);
    }

    /** The bill's payments once payments recorded them. */
    JsonNode payments(String billId, int expected) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(
                                json(get("/api/v1/payments?billId=" + billId)).path("items"))
                        .hasSize(expected));
        return json(get("/api/v1/payments?billId=" + billId)).path("items");
    }

    JsonNode openDrawer(String token, String openingFloat) {
        MvcTestResult opened = post(
                "/api/v1/drawer-sessions",
                token,
                "{\"registerId\": \"" + register + "\", \"openingFloat\": \"" + openingFloat + "\"}");
        assertThat(opened).hasStatus(201);
        return json(opened);
    }

    MvcTestResult get(String path) {
        return checked(client.get(path, owner));
    }

    MvcTestResult post(String path, String token, String body) {
        return checked(client.post(path, token, body));
    }

    MvcTestResult post(String path, String body) {
        return post(path, owner, body);
    }

    static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
