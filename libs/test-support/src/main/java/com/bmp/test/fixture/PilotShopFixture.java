package com.bmp.test.fixture;

import static com.bmp.test.fixture.PilotShop.checked;
import static com.bmp.test.fixture.PilotShop.etag;
import static com.bmp.test.fixture.PilotShop.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * The pilot shop of {@code docs/reference/domain-model.md} §2, created through the public API only: eggs, cooking
 * oil, branded and loose soya meat and Karola, with retail and wholesale prices, the wholesale quantity break at 300
 * eggs and VAT on the oil.
 *
 * <p><b>Every price, the 18 % VAT rate and the choice of taxed product are placeholders</b>, not the client's figures
 * (Requirements Understanding Document §9 leaves prices, taxes on the bill and the wholesale rule open). So is the oil
 * density, 1086.96 ml per kg, which makes the 18 kg can 19,565.28 ml until the measured yield is known. Tests may
 * rely on the placeholder values below, never on them being real.
 *
 * <p>Use: {@code PilotShop shop = PilotShopFixture.create(mvc, "Pilot shop")} in a test with the whole application
 * (catalog, pricing, tenant and iam modules) and PostgreSQL.
 */
public final class PilotShopFixture {

    /** Placeholder retail price of one egg by size (LKR). */
    public static final Map<String, String> EGG_RETAIL_PIECE =
            Map.of("XS", "40.00", "S", "45.00", "M", "50.00", "L", "55.00", "XL", "60.00");

    /** Placeholder surcharge per Golden Yellow egg. */
    public static final String GOLDEN_YELLOW_EXTRA = "5.00";

    /** Placeholder discount per egg on the wholesale list. */
    public static final String EGG_WHOLESALE_DISCOUNT = "5.00";

    /** Placeholder: a retail tray of 30 costs 29 eggs; a wholesale tray costs 30 wholesale eggs. */
    public static final int RETAIL_TRAY_PIECES_CHARGED = 29;

    /** Placeholder retail prices of the other products by SKU and packaging unit code. */
    public static final Map<String, Map<String, String>> RETAIL = Map.of(
            "OIL",
                    Map.of(
                            "bottle-1000",
                            "720.00",
                            "bottle-750",
                            "550.00",
                            "bottle-375",
                            "280.00",
                            "bottle-187",
                            "145.00"),
            "SOYA-MSL-G90", Map.of("pack", "120.00"),
            "SOYA-MSL-G180", Map.of("pack", "230.00"),
            "SOYA-VTM-G90", Map.of("pack", "125.00"),
            "SOYA-VTM-G180", Map.of("pack", "240.00"),
            "SOYA-LOOSE", Map.of("kg", "1100.00", "packet-250", "290.00", "packet-500", "560.00"),
            "KAROLA", Map.of("kg", "2400.00", "packet-250", "620.00", "packet-500", "1220.00"));

    /** Placeholder wholesale prices of the other products by SKU and packaging unit code. */
    public static final Map<String, Map<String, String>> WHOLESALE = Map.of(
            "OIL", Map.of("can", "14500.00"),
            "SOYA-LOOSE", Map.of("kg", "1000.00"),
            "KAROLA", Map.of("kg", "2200.00"));

    static final String EGGS = """
            {"code": "EGG", "name": "Eggs", "template": "VARIANT_MATRIX", "baseUnit": "piece",
             "attributes": [
               {"code": "COLOUR", "name": "Colour", "values": [{"code": "RED", "value": "Red"},
                  {"code": "WHITE", "value": "White"}, {"code": "GOLDEN_YELLOW", "value": "Golden Yellow"}]},
               {"code": "SIZE", "name": "Size", "values": [{"value": "XS"}, {"value": "S"}, {"value": "M"},
                  {"value": "L"}, {"value": "XL"}]}],
             "exclusions": [{"COLOUR": "GOLDEN_YELLOW", "SIZE": "XS"}, {"COLOUR": "GOLDEN_YELLOW", "SIZE": "S"},
                            {"COLOUR": "GOLDEN_YELLOW", "SIZE": "M"}],
             "packagingUnits": [{"code": "tray", "name": "Tray of 30", "qtyInBase": "30", "canBuy": true, "canSell": true}]}
            """;

    static final String OIL = """
            {"code": "OIL", "name": "Cooking oil", "template": "BULK_BREAK", "baseUnit": "ml", "taxCategoryId": "%s",
             "conversions": [{"fromUnit": "kg", "toUnit": "ml", "factor": "1086.96", "source": "DENSITY"}],
             "packagingUnits": [
               {"code": "can", "name": "Can 18 kg", "definedAs": {"value": "18", "unit": "kg"}, "canBuy": true, "canSell": true},
               {"code": "bottle-1000", "name": "Bottle 1000 ml", "qtyInBase": "1000", "canSell": true, "isDefaultSell": true},
               {"code": "bottle-750", "name": "Bottle 750 ml", "qtyInBase": "750", "canSell": true},
               {"code": "bottle-375", "name": "Bottle 375 ml", "qtyInBase": "375", "canSell": true},
               {"code": "bottle-187", "name": "Bottle 187.5 ml", "qtyInBase": "187.5", "canSell": true}]}
            """;

    static final String BRANDED_SOYA = """
            {"code": "SOYA", "name": "Soya meat", "template": "BRANDED_PACKET",
             "attributes": [
               {"code": "BRAND", "name": "Brand", "values": [{"code": "MSL", "value": "MySoy"},
                  {"code": "VTM", "value": "Vitamin Soy"}]},
               {"code": "SIZE", "name": "Packet size", "values": [{"code": "G90", "value": "90 g"},
                  {"code": "G180", "value": "180 g"}]}]}
            """;

    static final String LOOSE = """
            {"code": "%s", "name": "%s", "template": "LOOSE_REPACK", "baseUnit": "g",
             "packagingUnits": [
               {"code": "kg", "name": "Kilogram", "definedAs": {"value": "1", "unit": "kg"}, "canBuy": true, "canSell": true},
               {"code": "packet-250", "name": "Packet 250 g", "qtyInBase": "250", "canSell": true},
               {"code": "packet-500", "name": "Packet 500 g", "qtyInBase": "500", "canSell": true}]}
            """;

    /**
     * Minimum quantity of the kilogram prices of loose goods: one gram, so weighed sales below a kilogram are priced
     * (a price applies from its minimum quantity, default 1, PRC-02/PRC-04).
     */
    public static final String WEIGHED_MIN_QTY = "0.001";

    private static final Duration WAIT = Duration.ofSeconds(30);

    private PilotShopFixture() {}

    /** Creates a new tenant named {@code name} with the whole pilot catalog and its prices. */
    public static PilotShop create(MockMvcTester mvc, String name) {
        ApiClient client = new ApiClient(mvc);
        TenantId tenant = client.createTenant(name);
        String owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
        Map<String, String> lists = seededLists(client, owner);

        String vat = id(created(client.post(
                "/api/v1/tax-categories", owner, "{\"code\": \"VAT_STANDARD\", \"name\": \"VAT (placeholder)\"}")));
        created(client.post(
                "/api/v1/tax-rates",
                owner,
                "{\"taxCategoryId\": \"" + vat + "\", \"code\": \"VAT\", \"name\": \"VAT 18 % (placeholder)\","
                        + " \"ratePct\": \"18\", \"validFrom\": \"2000-01-01\"}"));

        List<JsonNode> products = new ArrayList<>();
        for (String body : List.of(
                EGGS,
                OIL.formatted(vat),
                BRANDED_SOYA,
                LOOSE.formatted("SOYA-LOOSE", "Soya meat (loose)"),
                LOOSE.formatted("KAROLA", "Karola (dried fish)"))) {
            products.add(json(created(client.post("/api/v1/products", owner, body))));
        }
        awaitPricingKnowsTheVariants(client, owner, products);

        String retailId = Objects.requireNonNull(lists.get("RETAIL"));
        String wholesaleId = Objects.requireNonNull(lists.get("WHOLESALE"));
        upsert(client, owner, retailId, rows(products, PilotShopFixture::retail));
        upsert(client, owner, wholesaleId, rows(products, PilotShopFixture::wholesale));
        String eggs = products.getFirst().path("id").asString();
        created(client.post("/api/v1/price-rules", owner, """
                {"code": "EGG-300", "name": "Eggs, 300 pieces and more (placeholder 5 %%)", "kind": "QTY_BREAK",
                 "conditions": {"channel": "WHOLESALE", "productIds": ["%s"], "minQtyBase": "300"},
                 "effect": {"type": "PERCENT_OFF", "value": "5"}, "priority": 10}
                """.formatted(eggs)));

        return new PilotShop(client, tenant, owner, products, retailId, wholesaleId, vat);
    }

    /** The placeholder retail price of one egg of this SKU ({@code EGG-<COLOUR>-<SIZE>}). */
    public static BigDecimal eggRetailPiece(String sku) {
        String size = sku.substring(sku.lastIndexOf('-') + 1);
        BigDecimal price = new BigDecimal(Objects.requireNonNull(EGG_RETAIL_PIECE.get(size), size));
        return sku.contains("GOLDEN_YELLOW") ? price.add(new BigDecimal(GOLDEN_YELLOW_EXTRA)) : price;
    }

    /** The placeholder wholesale price of one egg of this SKU. */
    public static BigDecimal eggWholesalePiece(String sku) {
        return eggRetailPiece(sku).subtract(new BigDecimal(EGG_WHOLESALE_DISCOUNT));
    }

    private static Map<String, String> retail(String sku) {
        if (!sku.startsWith("EGG-")) {
            return RETAIL.getOrDefault(sku, Map.of());
        }
        BigDecimal piece = eggRetailPiece(sku);
        return Map.of(
                "piece", money(piece), "tray", money(piece.multiply(BigDecimal.valueOf(RETAIL_TRAY_PIECES_CHARGED))));
    }

    private static Map<String, String> wholesale(String sku) {
        if (!sku.startsWith("EGG-")) {
            return WHOLESALE.getOrDefault(sku, Map.of());
        }
        BigDecimal piece = eggWholesalePiece(sku);
        return Map.of("piece", money(piece), "tray", money(piece.multiply(BigDecimal.valueOf(30))));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Waits until the tenant's roles and price lists are seeded (both follow TenantCreated asynchronously). */
    private static Map<String, String> seededLists(ApiClient client, String owner) {
        Map<String, String> lists = new HashMap<>();
        await().atMost(WAIT).untilAsserted(() -> {
            MvcTestResult result = client.get("/api/v1/price-lists", owner);
            assertThat(result).hasStatusOk();
            lists.clear();
            json(result)
                    .path("items")
                    .forEach(l ->
                            lists.put(l.path("code").asString(), l.path("id").asString()));
            assertThat(lists).containsOnlyKeys("RETAIL", "WHOLESALE");
        });
        return lists;
    }

    /** Waits until every sellable unit has reached the pricing replica (it follows catalog events asynchronously). */
    private static void awaitPricingKnowsTheVariants(ApiClient client, String owner, List<JsonNode> products) {
        StringJoiner lines = new StringJoiner(",");
        for (JsonNode product : products) {
            for (JsonNode variant : product.path("variants")) {
                for (JsonNode unit : variant.path("packagingUnits")) {
                    if (unit.path("canSell").asBoolean()) {
                        lines.add("{\"variantId\": \"" + variant.path("id").asString() + "\", \"packagingUnitId\": \""
                                + unit.path("id").asString() + "\", \"qty\": \"1\"}");
                    }
                }
            }
        }
        String basket = "{\"channel\": \"RETAIL\", \"lines\": [" + lines + "]}";
        await().atMost(WAIT).untilAsserted(() -> {
            MvcTestResult result = checked(client.post("/api/v1/prices:resolve", owner, basket));
            assertThat(result).hasStatusOk();
            assertThat(json(result).path("lines"))
                    .as("no line is unknown to pricing any more")
                    .allSatisfy(l -> assertThat(l.path("status").asString()).isNotEqualTo("NOT_SELLABLE"));
        });
    }

    private static List<String> rows(List<JsonNode> products, Function<String, Map<String, String>> sheet) {
        List<String> rows = new ArrayList<>();
        for (JsonNode product : products) {
            for (JsonNode variant : product.path("variants")) {
                sheet.apply(variant.path("sku").asString())
                        .forEach((unit, amount) -> rows.add(
                                "{\"variantId\": \"" + variant.path("id").asString()
                                        + "\", \"packagingUnitId\": \"" + PilotShop.unit(variant, unit)
                                        + (unit.equals("kg") ? "\", \"minQty\": \"" + WEIGHED_MIN_QTY : "")
                                        + "\", \"amount\": \"" + amount + "\"}"));
            }
        }
        return rows;
    }

    private static void upsert(ApiClient client, String owner, String listId, List<String> rows) {
        String path = "/api/v1/price-lists/" + listId + "/prices";
        MvcTestResult current = checked(client.get(path, owner));
        MvcTestResult result =
                checked(client.put(path, owner, "{\"prices\": [" + String.join(",", rows) + "]}", etag(current)));
        assertThat(result).hasStatusOk();
    }

    private static MvcTestResult created(MvcTestResult result) {
        checked(result);
        assertThat(result).hasStatus(201);
        return result;
    }

    private static String id(MvcTestResult result) {
        return json(result).path("id").asString();
    }
}
