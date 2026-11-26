package com.bmp.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.sync.SyncTestApplication.Devices;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.OpenApiValidation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** The pilot shop with register R1 and a till device bound to it; records are built as the till would send them. */
final class SyncTill {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("sync");

    final PilotShop shop;
    final ApiClient client;
    final TenantId tenant;
    final String owner;
    final UUID location;
    final UUID register;
    final DeviceId device;
    final String token;
    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;

    SyncTill(
            MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactions, Devices devices, String name) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.shop = PilotShopFixture.create(mvc, name + " " + UUID.randomUUID());
        this.client = new ApiClient(mvc);
        this.tenant = shop.tenant();
        this.owner = shop.owner();
        this.location = UUID.fromString(ApiClient.json(client.get("/api/v1/locations", owner))
                .path("items")
                .get(0)
                .path("id")
                .asString());
        MvcTestResult created = client.post(
                "/api/v1/registers",
                owner,
                "{\"code\": \"R1\", \"locationId\": \"" + location + "\", \"billPrefix\": \"R1\"}");
        assertThat(created).hasStatus(201);
        this.register = ApiClient.id(created);
        this.device = devices.bind(register, location);
        this.token = JwtTestTokens.builder()
                .tenant(tenant)
                .device(device)
                .roles("DEVICE")
                .build();
        String egg = shop.variant("EGG-RED-L").path("id").asString();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(this.<Long>sql(j ->
                                j.sql("""
                                        SELECT (SELECT count(*) FROM sales.sale_item WHERE variant_id::text = :v)
                                             + (SELECT count(*) FROM inventory.stock_item WHERE variant_id::text = :v)""").param("v", egg).query(Long.class).single()))
                        .isEqualTo(2L));
    }

    <T> T sql(Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc))));
    }

    static String record(String type, UUID id, String payload, UUID... dependsOn) {
        String deps = dependsOn.length == 0
                ? ""
                : ", \"dependsOn\": ["
                        + String.join(
                                ", ",
                                List.of(dependsOn).stream()
                                        .map(d -> "\"" + d + "\"")
                                        .toList()) + "]";
        return "{\"type\": \"" + type + "\", \"id\": \"" + id + "\"" + deps + ", \"payload\": " + payload + "}";
    }

    String shiftOpen(UUID id, Instant at) {
        return record("SHIFT_OPEN", id, "{\"openedAt\": \"" + at + "\"}");
    }

    String drawerOpen(UUID id, String openingFloat, Instant at) {
        return record("DRAWER_OPEN", id, "{\"openingFloat\": \"" + openingFloat + "\", \"openedAt\": \"" + at + "\"}");
    }

    /** A bill of {@code qty} large eggs at 55.00 paid in cash. */
    String bill(UUID id, long number, String qty, @Nullable UUID shift, Instant at, UUID... dependsOn) {
        return bill(id, UUID.randomUUID(), number, qty, shift, at, dependsOn);
    }

    /** The same, naming the bill's line. */
    String bill(UUID id, UUID line, long number, String qty, @Nullable UUID shift, Instant at, UUID... dependsOn) {
        String total = new BigDecimal(qty)
                .multiply(new BigDecimal("55.00"))
                .setScale(2)
                .toPlainString();
        JsonNode egg = shop.variant("EGG-RED-L");
        String payload = "{\"id\": \"" + id + "\", \"number\": \"R1-%06d\"".formatted(number)
                + (shift == null ? "" : ", \"shiftId\": \"" + shift + "\"")
                + ", \"channel\": \"RETAIL\", \"lines\": [{\"id\": \"" + line + "\", \"variantId\": \""
                + egg.path("id").asString() + "\", \"packagingUnitId\": \"" + PilotShop.unit(egg, "piece")
                + "\", \"qty\": \"" + qty + "\", \"unitPrice\": \"55.00\"}], \"tenders\": [{\"id\": \""
                + UUID.randomUUID() + "\", \"method\": \"CASH\", \"amount\": \"" + total + "\", \"tendered\": \""
                + total + "\"}], \"totals\": {\"subtotal\": \"" + total + "\", \"discountTotal\": \"0.00\", "
                + "\"taxTotal\": \"0.00\", \"grandTotal\": \"" + total + "\"}, \"completedAt\": \"" + at + "\"}";
        return record("BILL", id, payload, dependsOn);
    }

    MvcTestResult upload(@Nullable Instant deviceTime, String... records) {
        var request = mvc.post()
                .uri("/api/v1/sync/uploads")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"records\": [" + String.join(", ", records) + "]}");
        return checked(
                (deviceTime == null ? request : request.header("Device-Time", deviceTime.toString())).exchange());
    }

    MvcTestResult get(String path) {
        return checked(client.get(path, token));
    }

    MvcTestResult get(String path, String bearer) {
        return checked(client.get(path, bearer));
    }

    static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
