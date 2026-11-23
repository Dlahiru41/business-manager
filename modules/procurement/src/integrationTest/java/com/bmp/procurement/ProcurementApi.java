package com.bmp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.OpenApiValidation;
import java.util.Objects;
import java.util.UUID;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * Helpers for the procurement tests: a tenant with its first location, suppliers and catalog products made through
 * their own APIs, and contract validation of every procurement response.
 */
final class ProcurementApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("procurement");

    /** Eggs: bought by the piece (the base unit) or by the tray of 30. */
    static final String EGGS = """
            {"code": "EGG", "name": "Eggs", "template": "VARIANT_MATRIX", "baseUnit": "piece",
             "attributes": [{"code": "COLOUR", "name": "Colour", "values": [{"code": "RED", "value": "Red"}]},
                            {"code": "SIZE", "name": "Size", "values": [{"value": "S"}, {"value": "M"}, {"value": "L"}]}],
             "packagingUnits": [{"code": "tray", "name": "Tray of 30", "qtyInBase": "30", "canBuy": true, "canSell": true}]}
            """;

    /** Oil: bought by the 18 kg can only; bottles are sold, never bought. */
    static final String OIL = """
            {"code": "OIL", "name": "Cooking oil", "template": "BULK_BREAK", "baseUnit": "ml",
             "conversions": [{"fromUnit": "kg", "toUnit": "ml", "factor": "1086.96", "source": "DENSITY"}],
             "packagingUnits": [
               {"code": "can", "name": "Can 18 kg", "definedAs": {"value": "18", "unit": "kg"}, "canBuy": true, "canSell": true},
               {"code": "bottle-750", "name": "Bottle 750 ml", "qtyInBase": "750", "canSell": true}]}
            """;

    /** Milk: batches with an expiry date. */
    static final String MILK = """
            {"code": "MILK", "name": "Milk", "template": "SIMPLE", "tracking": {"batches": true, "expiry": true}}
            """;

    final ApiClient client;
    final TenantId tenant;
    final String owner;
    final String storekeeper;
    final UUID location;

    ProcurementApi(MockMvcTester mvc, String name) {
        this.client = new ApiClient(mvc);
        this.tenant = client.createTenant(name + " " + UUID.randomUUID());
        this.owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
        this.storekeeper = JwtTestTokens.forRole(tenant, SystemRole.STOREKEEPER);
        MvcTestResult locations = client.get("/api/v1/locations", owner);
        assertThat(locations).hasStatusOk();
        this.location = UUID.fromString(
                ApiClient.json(locations).path("items").get(0).path("id").asString());
    }

    /** A supplier with payment terms. */
    UUID supplier(String name, int paymentTermsDays) {
        MvcTestResult created =
                client.post("/api/v1/parties", owner, "{\"type\": \"SUPPLIER\", \"displayName\": \"" + name + "\"}");
        assertThat(created).hasStatus(201);
        String path = "/api/v1/parties/" + ApiClient.id(created);
        assertThat(client.put(
                        path + "/credit-policy",
                        owner,
                        "{\"creditAllowed\": false, \"creditLimit\": \"0.00\", \"paymentTermsDays\": "
                                + paymentTermsDays + "}",
                        ApiClient.etag(created)))
                .hasStatusOk();
        return ApiClient.id(created);
    }

    UUID customer(String name) {
        MvcTestResult created =
                client.post("/api/v1/parties", owner, "{\"type\": \"CUSTOMER\", \"displayName\": \"" + name + "\"}");
        assertThat(created).hasStatus(201);
        return ApiClient.id(created);
    }

    JsonNode product(String body) {
        MvcTestResult created = client.post("/api/v1/products", owner, body);
        assertThat(created).hasStatus(201);
        return ApiClient.json(created);
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

    MvcTestResult action(String path) {
        return checked(client.action(path, owner));
    }

    MvcTestResult patch(String path, String body, String ifMatch) {
        return checked(client.patch(path, owner, body, ifMatch));
    }

    static JsonNode variant(JsonNode product, String sku) {
        for (JsonNode variant : product.path("variants")) {
            if (variant.path("sku").asString().equals(sku)) {
                return variant;
            }
        }
        throw new AssertionError("No variant " + sku);
    }

    static String id(JsonNode node) {
        return node.path("id").asString();
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

    static String etag(MvcTestResult result) {
        return Objects.requireNonNull(ApiClient.etag(result), "ETag");
    }

    static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
