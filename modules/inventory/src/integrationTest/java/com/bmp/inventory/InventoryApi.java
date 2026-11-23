package com.bmp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.OpenApiValidation;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Helpers for the inventory tests: a tenant with a location, catalog products whose variants reached the inventory
 * replica, SQL in the tenant's context, and contract validation of every inventory response.
 */
final class InventoryApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("inventory");

    static final String OIL = """
            {"code": "OIL", "name": "Cooking oil", "template": "BULK_BREAK", "baseUnit": "ml",
             "conversions": [{"fromUnit": "kg", "toUnit": "ml", "factor": "1086.96", "source": "DENSITY"}],
             "packagingUnits": [
               {"code": "can", "name": "Can 18 kg", "definedAs": {"value": "18", "unit": "kg"}, "canBuy": true, "canSell": true},
               {"code": "bottle-750", "name": "Bottle 750 ml", "qtyInBase": "750", "canSell": true}]}
            """;

    static final String EGGS = """
            {"code": "EGG", "name": "Eggs", "template": "VARIANT_MATRIX", "baseUnit": "piece",
             "attributes": [{"code": "COLOUR", "name": "Colour", "values": [{"code": "RED", "value": "Red"}]},
                            {"code": "SIZE", "name": "Size", "values": [{"value": "S"}, {"value": "M"}, {"value": "L"}]}],
             "packagingUnits": [{"code": "tray", "name": "Tray of 30", "qtyInBase": "30", "canBuy": true, "canSell": true}]}
            """;

    static final String KAROLA = """
            {"code": "KAROLA", "name": "Karola", "template": "LOOSE_REPACK", "baseUnit": "g",
             "packagingUnits": [
               {"code": "kg", "name": "Kilogram", "definedAs": {"value": "1", "unit": "kg"}, "canBuy": true, "canSell": true},
               {"code": "packet-250", "name": "Packet 250 g", "qtyInBase": "250", "canSell": true}]}
            """;

    final ApiClient client;
    final TenantId tenant;
    final String owner;
    final UUID location;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;

    InventoryApi(MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactions, String name) {
        this.client = new ApiClient(mvc);
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tenant = client.createTenant(name + " " + UUID.randomUUID());
        this.owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
        MvcTestResult locations = client.get("/api/v1/locations", owner);
        assertThat(locations).hasStatusOk();
        this.location = UUID.fromString(
                ApiClient.json(locations).path("items").get(0).path("id").asString());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(this.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM inventory.location_ref WHERE location_id = :l")
                                        .param("l", location)
                                        .query(Long.class)
                                        .single()))
                        .as("LocationCreated reached inventory")
                        .isEqualTo(1L));
    }

    /** Runs code as the application role in the tenant's context, in a transaction (row-level security applies). */
    <T> T sql(Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc))));
    }

    <T> T inTenant(java.util.function.Supplier<T> action) {
        return Objects.requireNonNull(
                TenantContext.callWith(tenant, () -> new TransactionTemplate(transactions).execute(s -> action.get())));
    }

    /** Creates a catalog product and waits until all its variants reached the inventory replica. */
    JsonNode product(String body) {
        MvcTestResult created = client.post("/api/v1/products", owner, body);
        assertThat(created).hasStatus(201);
        JsonNode product = ApiClient.json(created);
        long variants = product.path("variants").size();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(this.<Long>sql(j -> j.sql(
                                        "SELECT count(*) FROM inventory.stock_item WHERE product_id = :p")
                                .param("p", UUID.fromString(product.path("id").asString()))
                                .query(Long.class)
                                .single()))
                        .isEqualTo(variants));
        return product;
    }

    MvcTestResult get(String path) {
        return checked(client.get(path, owner));
    }

    MvcTestResult post(String path, String body) {
        return checked(client.post(path, owner, body));
    }

    MvcTestResult put(String path, String body, @org.jspecify.annotations.Nullable String ifMatch) {
        return checked(client.put(path, owner, body, ifMatch));
    }

    static JsonNode variant(JsonNode product, String sku) {
        for (JsonNode variant : product.path("variants")) {
            if (variant.path("sku").asString().equals(sku)) {
                return variant;
            }
        }
        throw new AssertionError("No variant " + sku);
    }

    static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").asString());
    }

    static String unit(JsonNode variant, String code) {
        for (JsonNode unit : variant.path("packagingUnits")) {
            if (unit.path("code").asString().equals(code)) {
                return unit.path("id").asString();
            }
        }
        throw new AssertionError("No packaging unit " + code);
    }

    static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
