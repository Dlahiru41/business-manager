package com.bmp.catalog;

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

/** Request helpers and pilot shop payloads for the catalog API tests; every response is checked against the contract. */
final class CatalogApi {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("catalog");

    static final String EGGS = """
            {"code": "EGG", "name": "Eggs", "template": "VARIANT_MATRIX", "baseUnit": "piece",
             "attributes": [
               {"code": "COLOUR", "name": "Colour", "sort": 1, "values": [
                 {"code": "RED", "value": "Red"}, {"code": "WHITE", "value": "White"},
                 {"code": "GOLDEN_YELLOW", "value": "Golden Yellow"}]},
               {"code": "SIZE", "name": "Size", "sort": 2, "values": [
                 {"value": "XS"}, {"value": "S"}, {"value": "M"}, {"value": "L"}, {"value": "XL"}]}],
             "exclusions": [{"COLOUR": "GOLDEN_YELLOW", "SIZE": "XS"}, {"COLOUR": "GOLDEN_YELLOW", "SIZE": "S"},
                            {"COLOUR": "GOLDEN_YELLOW", "SIZE": "M"}],
             "packagingUnits": [{"code": "tray", "name": "Tray of 30", "qtyInBase": "30", "canBuy": true, "canSell": true}]}
            """;

    static final String OIL = """
            {"code": "OIL", "name": "Cooking oil", "template": "BULK_BREAK", "baseUnit": "ml",
             "conversions": [{"fromUnit": "kg", "toUnit": "ml", "factor": "1086.96", "source": "DENSITY"}],
             "packagingUnits": [
               {"code": "can", "name": "Can 18 kg", "definedAs": {"value": "18", "unit": "kg"},
                "canBuy": true, "canSell": true},
               {"code": "bottle-1000", "name": "Bottle 1000 ml", "qtyInBase": "1000", "canSell": true,
                "isDefaultSell": true},
               {"code": "bottle-750", "name": "Bottle 750 ml", "qtyInBase": "750", "canSell": true},
               {"code": "bottle-375", "name": "Bottle 375 ml", "qtyInBase": "375", "canSell": true},
               {"code": "bottle-187", "name": "Bottle 187.5 ml", "qtyInBase": "187.5", "canSell": true}]}
            """;

    static final String SOYA = """
            {"code": "SOYA", "name": "Soya meat", "template": "BRANDED_PACKET",
             "attributes": [
               {"code": "BRAND", "name": "Brand", "values": [{"code": "MSL", "value": "MySoy"},
                                                            {"code": "VTM", "value": "Vitamin Soy"}]},
               {"code": "SIZE", "name": "Packet size", "values": [{"code": "G90", "value": "90 g"},
                                                                  {"code": "G180", "value": "180 g"}]}]}
            """;

    static final String KAROLA = """
            {"code": "KAROLA", "name": "Karola (dried fish)", "template": "LOOSE_REPACK", "baseUnit": "g",
             "packagingUnits": [
               {"code": "kg", "name": "Kilogram", "definedAs": {"value": "1", "unit": "kg"},
                "canBuy": true, "canSell": true},
               {"code": "packet-250", "name": "Packet 250 g", "qtyInBase": "250", "canSell": true},
               {"code": "packet-500", "name": "Packet 500 g", "qtyInBase": "500", "canSell": true}]}
            """;

    final ApiClient client;
    final TenantId tenant;
    final String owner;

    CatalogApi(MockMvcTester mvc, String tenantName) {
        this.client = new ApiClient(mvc);
        this.tenant = client.createTenant(tenantName + " " + UUID.randomUUID());
        this.owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
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

    MvcTestResult action(String path) {
        return checked(client.action(path, owner));
    }

    MvcTestResult patch(String path, String body, String ifMatch) {
        return checked(client.patch(path, owner, body, ifMatch));
    }

    MvcTestResult put(String path, String body, String ifMatch) {
        return checked(client.put(path, owner, body, ifMatch));
    }

    MvcTestResult delete(String path) {
        return checked(client.delete(path, owner));
    }

    /** Creates a product and returns its body (201 asserted). */
    JsonNode create(String product) {
        MvcTestResult created = post("/api/v1/products", product);
        assertThat(created).hasStatus(201);
        return json(created);
    }

    /** The variant of a product body with this SKU. */
    static JsonNode variant(JsonNode product, String sku) {
        for (JsonNode variant : product.path("variants")) {
            if (variant.path("sku").asString().equals(sku)) {
                return variant;
            }
        }
        throw new AssertionError("No variant " + sku);
    }

    /** The packaging unit of a variant body with this code. */
    static JsonNode unit(JsonNode variant, String code) {
        for (JsonNode unit : variant.path("packagingUnits")) {
            if (unit.path("code").asString().equals(code)) {
                return unit;
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

    /** Problem responses are part of the contract too; 401/403 bodies are checked by the security test. */
    private static MvcTestResult checked(MvcTestResult result) {
        CONTRACT.assertConforms(result);
        return result;
    }
}
