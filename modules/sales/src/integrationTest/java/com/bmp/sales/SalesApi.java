package com.bmp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
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
 * The pilot shop with a register R1 and an open shift, its sellable variants replicated into sales, and helpers that
 * build bills as a till would. Every sales response is checked against the contract.
 */
final class SalesApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("sales");

    final PilotShop shop;
    final ApiClient client;
    final TenantId tenant;
    final String owner;
    final String cashier;
    final UUID location;
    final UUID register;
    final UUID shift;
    final String prefix;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;
    private final AtomicInteger sequence = new AtomicInteger(40);

    SalesApi(MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactions, String name) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.shop = PilotShopFixture.create(mvc, name + " " + UUID.randomUUID());
        this.client = new ApiClient(mvc);
        this.tenant = shop.tenant();
        this.owner = shop.owner();
        this.cashier = JwtTestTokens.forRole(tenant, SystemRole.CASHIER);
        this.location = UUID.fromString(ApiClient.json(client.get("/api/v1/locations", owner))
                .path("items")
                .get(0)
                .path("id")
                .asString());
        this.prefix = "R1";
        this.register = id(post(
                "/api/v1/registers",
                "{\"code\": \"R1\", \"name\": \"Front counter\", \"locationId\": \"" + location + "\", "
                        + "\"billPrefix\": \"R1\"}"));
        this.shift = id(post("/api/v1/shifts", "{\"registerId\": \"" + register + "\"}"));
        List<String> variants = new ArrayList<>();
        for (String sku : List.of("EGG-RED-L", "EGG-RED-M", "OIL", "KAROLA")) {
            variants.add(variant(sku).path("id").asString());
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(this.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM sales.sale_item WHERE variant_id::text = ANY (:ids)")
                                        .param("ids", variants.toArray(String[]::new))
                                        .query(Long.class)
                                        .single()))
                        .as("the catalog's variants reached sales")
                        .isEqualTo(4L));
    }

    <T> T sql(Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc))));
    }

    JsonNode variant(String sku) {
        return shop.variant(sku);
    }

    String unit(String sku, String code) {
        return PilotShop.unit(variant(sku), code);
    }

    String nextNumber() {
        return prefix + "-" + "%06d".formatted(sequence.incrementAndGet());
    }

    /** A bill line; {@code extra} adds fields such as {@code , "lineDiscount": "5.00"}. */
    String line(String sku, String unitCode, String qty, @Nullable String unitPrice, String extra) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"variantId\": \""
                + variant(sku).path("id").asString()
                + "\", \"packagingUnitId\": \"" + unit(sku, unitCode) + "\", \"qty\": \"" + qty + "\""
                + (unitPrice == null ? "" : ", \"unitPrice\": \"" + unitPrice + "\"") + extra + "}";
    }

    static String cash(String amount, String tendered) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"method\": \"CASH\", \"amount\": \"" + amount
                + "\", \"tendered\": \"" + tendered + "\"}";
    }

    static String tender(String method, String amount, @Nullable String reference) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"method\": \"" + method + "\", \"amount\": \"" + amount + "\""
                + (reference == null ? "" : ", \"reference\": \"" + reference + "\"") + "}";
    }

    static String totals(String subtotal, String discount, String tax, String grand) {
        return "{\"subtotal\": \"" + subtotal + "\", \"discountTotal\": \"" + discount + "\", \"taxTotal\": \"" + tax
                + "\", \"grandTotal\": \"" + grand + "\"}";
    }

    /** A completed bill as the till submits it; {@code extra} adds header fields. */
    String completed(UUID id, String number, String extra, List<String> lines, List<String> tenders, String totals) {
        return completed("RETAIL", id, number, extra, lines, tenders, totals);
    }

    String completed(
            String channel,
            UUID id,
            String number,
            String extra,
            List<String> lines,
            List<String> tenders,
            String totals) {
        return "{\"id\": \"" + id + "\", \"number\": \"" + number + "\", \"registerId\": \"" + register
                + "\", \"channel\": \"" + channel + "\"" + extra + ", \"lines\": [" + String.join(", ", lines)
                + "], \"complete\": true, \"tenders\": [" + String.join(", ", tenders) + "], \"totals\": " + totals
                + "}";
    }

    MvcTestResult submit(String body) {
        return checked(client.post("/api/v1/bills", owner, body));
    }

    MvcTestResult get(String path) {
        return checked(client.get(path, owner));
    }

    MvcTestResult post(String path, String body) {
        return checked(client.post(path, owner, body));
    }

    MvcTestResult post(String path, String token, String body) {
        return checked(client.post(path, token, body));
    }

    MvcTestResult patch(String path, String body, @Nullable String ifMatch) {
        return checked(client.patch(path, owner, body, ifMatch));
    }

    /** Changes tenant settings of a group. */
    void settings(String group, String values) {
        MvcTestResult current = client.get("/api/v1/settings/" + group, owner);
        assertThat(client.patch(
                        "/api/v1/settings/" + group, owner, "{\"values\": " + values + "}", ApiClient.etag(current)))
                .hasStatusOk();
    }

    /** A customer allowed credit up to {@code limit}, replicated into sales. */
    UUID creditCustomer(String name, String limit) {
        MvcTestResult created =
                client.post("/api/v1/parties", owner, "{\"type\": \"CUSTOMER\", \"displayName\": \"" + name + "\"}");
        assertThat(created).hasStatus(201);
        UUID customer = ApiClient.id(created);
        assertThat(client.put(
                        "/api/v1/parties/" + customer + "/credit-policy",
                        owner,
                        "{\"creditAllowed\": true, \"creditLimit\": \"" + limit + "\", \"paymentTermsDays\": 30}",
                        ApiClient.etag(created)))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(this.<Boolean>sql(j -> j.sql(
                                        "SELECT coalesce(bool_or(credit_allowed), false) FROM sales.customer_ref WHERE party_id = :p")
                                .param("p", customer)
                                .query(Boolean.class)
                                .single()))
                        .isTrue());
        return customer;
    }

    static UUID id(MvcTestResult result) {
        assertThat(result.getResponse().getStatus()).as("status").isBetween(200, 201);
        return UUID.fromString(json(result).path("id").asString());
    }

    static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
