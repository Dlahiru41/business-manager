package com.bmp;

import static com.bmp.test.fixture.PilotShop.etag;
import static com.bmp.test.fixture.PilotShop.json;
import static com.bmp.test.fixture.PilotShop.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.TestJwtConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * T01-07: the pilot shop of domain-model.md §2 set up through the public API of the assembled application, priced and
 * billed. Prices and the VAT rate are the fixture's placeholders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithPostgres
@Import(TestJwtConfiguration.class)
class CatalogPricingFlowIT {

    @Autowired
    MockMvcTester mvc;

    private PilotShop shop;

    @BeforeEach
    void setUp() {
        shop = PilotShopFixture.create(mvc, "Pilot shop " + UUID.randomUUID());
    }

    private static List<String> skus(JsonNode product) {
        List<String> skus = new ArrayList<>();
        product.path("variants").forEach(v -> skus.add(v.path("sku").asString()));
        return skus;
    }

    private static JsonNode unitNode(JsonNode variant, String code) {
        for (JsonNode unit : variant.path("packagingUnits")) {
            if (unit.path("code").asString().equals(code)) {
                return unit;
            }
        }
        throw new AssertionError("No packaging unit " + code);
    }

    private static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    private List<JsonNode> definitions(String kind, JsonNode variant) {
        List<JsonNode> definitions = new ArrayList<>();
        json(shop.get("/api/v1/transformation-definitions?kind=" + kind + "&variantId="
                        + variant.path("id").asString()))
                .path("items")
                .forEach(definitions::add);
        return definitions;
    }

    private List<JsonNode> prices(String listId) {
        List<JsonNode> prices = new ArrayList<>();
        json(shop.get("/api/v1/price-lists/" + listId + "/prices?limit=200"))
                .path("items")
                .forEach(prices::add);
        return prices;
    }

    private JsonNode resolve(String channel, String... lines) {
        MvcTestResult resolved = shop.post(
                "/api/v1/prices:resolve",
                "{\"channel\": \"" + channel + "\", \"lines\": [" + String.join(",", lines) + "]}");
        assertThat(resolved).hasStatusOk();
        return json(resolved);
    }

    private static JsonNode line(JsonNode basket, String lineId) {
        for (JsonNode line : basket.path("lines")) {
            if (line.path("lineId").asString().equals(lineId)) {
                return line;
            }
        }
        throw new AssertionError("No line " + lineId);
    }

    private static String money(JsonNode node) {
        return node.path("amount").asString();
    }

    @Test
    void eggsAreTwelveVariantsEachWithARetailAndAWholesalePrice() {
        JsonNode eggs = shop.product("EGG");

        assertThat(skus(eggs))
                .hasSize(12)
                .contains("EGG-RED-XS", "EGG-WHITE-XL", "EGG-GOLDEN_YELLOW-L", "EGG-GOLDEN_YELLOW-XL")
                .doesNotContain("EGG-GOLDEN_YELLOW-XS", "EGG-GOLDEN_YELLOW-S", "EGG-GOLDEN_YELLOW-M");
        List<String> eggIds = new ArrayList<>();
        eggs.path("variants").forEach(v -> eggIds.add(v.path("id").asString()));
        for (String list : List.of(shop.retailListId(), shop.wholesaleListId())) {
            assertThat(prices(list).stream()
                            .filter(p -> eggIds.contains(p.path("variantId").asString()))
                            .map(p -> p.path("variantId").asString())
                            .distinct())
                    .as("every egg variant is priced on list %s", list)
                    .hasSize(12);
        }
        JsonNode rules = json(shop.get("/api/v1/price-rules?kind=QTY_BREAK"));
        assertThat(rules.path("items")
                        .get(0)
                        .path("conditions")
                        .path("minQtyBase")
                        .asString())
                .isEqualTo("300");
    }

    @Test
    void oilIsBoughtByTheCanAndSoldInBottlesOpenedFromIt() {
        JsonNode oil = shop.variant("OIL");
        JsonNode can = unitNode(oil, "can");

        assertThat(shop.product("OIL").path("baseUnit").asString()).isEqualTo("ml");
        assertThat(oil.path("stockStates").toString()).isEqualTo("[\"SEALED\",\"OPEN\"]");
        assertThat(oil.path("sellMode").asString()).isEqualTo("BREAK_ON_SALE");
        assertThat(decimal(can.path("qtyInBase"))).isEqualByComparingTo("19565.28");
        assertThat(can.path("stockState").asString()).isEqualTo("SEALED");
        assertThat(can.path("canBuy").asBoolean()).isTrue();
        List<JsonNode> bottles = new ArrayList<>();
        oil.path("packagingUnits").forEach(u -> {
            if (u.path("code").asString().startsWith("bottle-")) {
                bottles.add(u);
            }
        });
        assertThat(bottles).hasSize(4).allSatisfy(b -> {
            assertThat(b.path("canSell").asBoolean()).isTrue();
            assertThat(b.path("stockState").asString()).isEqualTo("OPEN");
        });
        List<JsonNode> open = definitions("OPEN", oil);
        assertThat(open).singleElement().satisfies(d -> {
            assertThat(d.path("status").asString()).isEqualTo("PUBLISHED");
            assertThat(d.path("lines"))
                    .anySatisfy(l -> {
                        assertThat(l.path("direction").asString()).isEqualTo("IN");
                        assertThat(l.path("stockState").asString()).isEqualTo("SEALED");
                    })
                    .anySatisfy(l -> {
                        assertThat(l.path("direction").asString()).isEqualTo("OUT");
                        assertThat(l.path("stockState").asString()).isEqualTo("OPEN");
                        assertThat(decimal(l.path("qtyBase"))).isEqualByComparingTo("19565.28");
                    });
        });
        assertThat(prices(shop.wholesaleListId()))
                .as("the can has a wholesale price")
                .anySatisfy(p -> assertThat(p.path("packagingUnitId").asString())
                        .isEqualTo(can.path("id").asString()));
    }

    @Test
    void soyaComesInBrandedPacketsAndLooseGoodsAreRepackedFromGrams() {
        JsonNode soya = shop.product("SOYA");

        assertThat(skus(soya))
                .containsExactlyInAnyOrder("SOYA-MSL-G90", "SOYA-MSL-G180", "SOYA-VTM-G90", "SOYA-VTM-G180");
        assertThat(soya.path("baseUnit").asString()).isEqualTo("pack");
        for (String code : List.of("SOYA-LOOSE", "KAROLA")) {
            JsonNode product = shop.product(code);
            JsonNode variant = shop.variant(code);
            assertThat(product.path("baseUnit").asString()).as(code).isEqualTo("g");
            assertThat(decimal(unitNode(variant, "kg").path("qtyInBase"))).isEqualByComparingTo("1000");
            assertThat(decimal(unitNode(variant, "packet-250").path("qtyInBase")))
                    .isEqualByComparingTo("250");
            assertThat(decimal(unitNode(variant, "packet-500").path("qtyInBase")))
                    .isEqualByComparingTo("500");
            assertThat(definitions("REPACK", variant))
                    .as("%s has a published REPACK definition per own packet", code)
                    .hasSize(2)
                    .allSatisfy(d -> assertThat(d.path("status").asString()).isEqualTo("PUBLISHED"));
        }
    }

    @Test
    void aMixedRetailBasketIsPricedFromTheRetailListWithVatOnTheOil() {
        JsonNode basket = resolve(
                "RETAIL",
                shop.line("eggs", "EGG-RED-L", "piece", "10"),
                shop.line("tray", "EGG-GOLDEN_YELLOW-XL", "tray", "1"),
                shop.line("oil", "OIL", "bottle-750", "2"),
                shop.line("soya", "SOYA-VTM-G180", "pack", "3"),
                shop.line("karola", "KAROLA", "packet-250", "1"),
                shop.line("loose", "SOYA-LOOSE", "kg", "0.75"));

        assertThat(basket.path("lines")).allSatisfy(l -> {
            assertThat(l.path("status").asString()).isEqualTo("PRICED");
            assertThat(l.path("source").asString()).isEqualTo("LIST");
            assertThat(l.path("priceListId").asString()).isEqualTo(shop.retailListId());
            assertThat(l.path("priceListVersion").asInt()).isEqualTo(1);
        });
        assertThat(money(line(basket, "eggs").path("lineTotal"))).isEqualTo("550.00");
        assertThat(money(line(basket, "tray").path("unitPrice")))
                .as("29 × 65.00")
                .isEqualTo("1885.00");
        assertThat(money(line(basket, "soya").path("lineTotal"))).isEqualTo("720.00");
        assertThat(money(line(basket, "karola").path("lineTotal"))).isEqualTo("620.00");
        assertThat(money(line(basket, "loose").path("lineTotal")))
                .as("0.75 kg × 1100.00")
                .isEqualTo("825.00");
        JsonNode oil = line(basket, "oil");
        assertThat(money(oil.path("lineTotal"))).isEqualTo("1100.00");
        assertThat(oil.path("taxInclusive").asBoolean()).isTrue();
        assertThat(money(oil.path("taxAmount"))).as("1100 × 18 / 118").isEqualTo("167.80");
        assertThat(money(oil.path("netAmount"))).isEqualTo("932.20");
        assertThat(oil.path("taxes").get(0).path("code").asString()).isEqualTo("VAT");
        assertThat(money(line(basket, "eggs").path("taxAmount")))
                .as("eggs carry no tax")
                .isEqualTo("0.00");
        assertThat(money(basket.path("totals").path("lineTotal"))).isEqualTo("5700.00");
        assertThat(money(basket.path("totals").path("taxAmount"))).isEqualTo("167.80");
    }

    @Test
    void aWholesaleBasketUsesWholesalePricesAndTheQuantityBreakFromThreeHundredEggs() {
        JsonNode basket = resolve(
                "WHOLESALE",
                shop.line("trays", "EGG-RED-L", "tray", "10"),
                shop.line("pieces", "EGG-WHITE-M", "piece", "299"),
                shop.line("can", "OIL", "can", "1"),
                shop.line("karola", "KAROLA", "kg", "5"));

        assertThat(basket.path("lines")).allSatisfy(l -> {
            assertThat(l.path("status").asString()).isEqualTo("PRICED");
            assertThat(l.path("priceListId").asString()).isEqualTo(shop.wholesaleListId());
            assertThat(l.path("priceListVersion").asInt()).isEqualTo(1);
        });
        JsonNode trays = line(basket, "trays");
        assertThat(money(trays.path("unitPrice"))).as("30 × 50.00").isEqualTo("1500.00");
        assertThat(trays.path("source").asString()).isEqualTo("RULE:EGG-300");
        assertThat(trays.path("appliedRules").toString()).isEqualTo("[\"EGG-300\"]");
        assertThat(money(trays.path("discount"))).as("5 % of 15000.00").isEqualTo("750.00");
        assertThat(money(trays.path("lineTotal"))).isEqualTo("14250.00");
        JsonNode pieces = line(basket, "pieces");
        assertThat(pieces.path("source").asString())
                .as("299 eggs are below the break")
                .isEqualTo("LIST");
        assertThat(money(pieces.path("lineTotal"))).isEqualTo("13455.00");
        JsonNode can = line(basket, "can");
        assertThat(money(can.path("lineTotal"))).isEqualTo("14500.00");
        assertThat(money(can.path("taxAmount"))).as("14500 × 18 / 118").isEqualTo("2211.86");
        assertThat(money(line(basket, "karola").path("lineTotal"))).isEqualTo("11000.00");
        assertThat(money(basket.path("totals").path("lineTotal"))).isEqualTo("53205.00");
    }

    @Test
    void cat14AMeasuredOilYieldResizesTheCanForLaterConversions() {
        String product = "/api/v1/products/" + shop.product("OIL").path("id").asString();
        MvcTestResult current = shop.get(product);

        MvcTestResult changed = shop.put(
                product + "/conversions",
                "{\"conversions\": [{\"fromUnit\": \"kg\", \"toUnit\": \"ml\", \"factor\": \"1087.5\","
                        + " \"source\": \"MEASURED_YIELD\"}]}",
                etag(current));

        assertThat(changed).hasStatusOk();
        JsonNode oil = json(shop.get(product + "?include=variants,units"))
                .path("variants")
                .get(0);
        assertThat(decimal(unitNode(oil, "can").path("qtyInBase")))
                .as("18 kg × 1087.5 ml/kg")
                .isEqualByComparingTo("19575");
        assertThat(decimal(unitNode(oil, "bottle-750").path("qtyInBase")))
                .as("bottles are defined in ml and keep their size")
                .isEqualByComparingTo("750");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(definitions("OPEN", oil))
                        .filteredOn(d -> d.path("status").asString().equals("PUBLISHED"))
                        .singleElement()
                        .satisfies(d -> {
                            assertThat(d.path("defVersion").asInt()).isEqualTo(2);
                            assertThat(d.path("lines"))
                                    .filteredOn(
                                            l -> l.path("direction").asString().equals("OUT"))
                                    .singleElement()
                                    .satisfies(l -> assertThat(decimal(l.path("qtyBase")))
                                            .isEqualByComparingTo("19575"));
                        }));
        assertThat(unit(oil, "can")).isEqualTo(unit(shop.variant("OIL"), "can"));
    }
}
