package com.bmp.pricing;

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
 * Request helpers for the pricing API tests: a tenant with its seeded lists, catalog products whose variants reached
 * the pricing replica, and contract validation of every response.
 */
final class PricingApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("pricing");

    static final String EGGS = """
            {"code": "EGG", "name": "Eggs", "template": "VARIANT_MATRIX", "baseUnit": "piece",
             "attributes": [{"code": "COLOUR", "name": "Colour", "values": [{"code": "RED", "value": "Red"}]},
                            {"code": "SIZE", "name": "Size", "values": [{"value": "L"}, {"value": "XL"}]}],
             "packagingUnits": [{"code": "tray", "name": "Tray of 30", "qtyInBase": "30", "canBuy": true, "canSell": true}]}
            """;

    static final String OIL = """
            {"code": "OIL", "name": "Cooking oil", "template": "BULK_BREAK", "baseUnit": "ml",
             "conversions": [{"fromUnit": "kg", "toUnit": "ml", "factor": "1086.96", "source": "DENSITY"}],
             "packagingUnits": [
               {"code": "can", "name": "Can 18 kg", "definedAs": {"value": "18", "unit": "kg"}, "canBuy": true, "canSell": true},
               {"code": "bottle-750", "name": "Bottle 750 ml", "qtyInBase": "750", "canSell": true}]}
            """;

    final ApiClient client;
    final TenantId tenant;
    final String owner;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;

    PricingApi(MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactions, String name) {
        this.client = new ApiClient(mvc);
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tenant = client.createTenant(name + " " + UUID.randomUUID());
        this.owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(this.<Long>sql(j -> j.sql("SELECT count(*) FROM pricing.price_list")
                                .query(Long.class)
                                .single()))
                        .as("Retail and Wholesale are seeded on TenantCreated")
                        .isEqualTo(2L));
    }

    /** Runs SQL as the application role in the tenant's context (row-level security applies). */
    <T> T sql(Function<JdbcClient, T> query) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc))));
    }

    String token(SystemRole role) {
        return JwtTestTokens.forRole(tenant, role);
    }

    MvcTestResult get(String path) {
        return checked(client.get(path, owner));
    }

    MvcTestResult post(String path, String body) {
        return checked(client.post(path, owner, body));
    }

    MvcTestResult patch(String path, String body, String ifMatch) {
        return checked(client.patch(path, owner, body, ifMatch));
    }

    MvcTestResult put(String path, String body, String ifMatch) {
        return checked(client.put(path, owner, body, ifMatch));
    }

    /** The id of a seeded or created list by code. */
    String list(String code) {
        for (JsonNode list : json(get("/api/v1/price-lists")).path("items")) {
            if (list.path("code").asString().equals(code)) {
                return list.path("id").asString();
            }
        }
        throw new AssertionError("No price list " + code);
    }

    /** Creates a catalog product and waits until all its variants reached the pricing replica. */
    JsonNode product(String body) {
        MvcTestResult created = client.post("/api/v1/products", owner, body);
        assertThat(created).hasStatus(201);
        JsonNode product = json(created);
        awaitReplicas(product.path("variants").size(), product.path("id").asString());
        return product;
    }

    /** Waits until the replica holds the product's variants at least at this catalog version. */
    void awaitReplicas(int variants, String productId) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(this.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM pricing.variant_price_ref WHERE product_id = :p")
                                        .param("p", UUID.fromString(productId))
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo((long) variants));
    }

    static JsonNode variant(JsonNode product, String sku) {
        for (JsonNode variant : product.path("variants")) {
            if (variant.path("sku").asString().equals(sku)) {
                return variant;
            }
        }
        throw new AssertionError("No variant " + sku);
    }

    static String unit(JsonNode variant, String code) {
        for (JsonNode unit : variant.path("packagingUnits")) {
            if (unit.path("code").asString().equals(code)) {
                return unit.path("id").asString();
            }
        }
        throw new AssertionError("No packaging unit " + code);
    }

    /** One price row for an upsert. */
    static String row(JsonNode variant, String unitCode, String amount) {
        return "{\"variantId\": \"" + variant.path("id").asString() + "\", \"packagingUnitId\": \""
                + unit(variant, unitCode) + "\", \"amount\": \"" + amount + "\"}";
    }

    static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    static String etag(MvcTestResult result) {
        return Objects.requireNonNull(ApiClient.etag(result), "ETag");
    }

    private static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
