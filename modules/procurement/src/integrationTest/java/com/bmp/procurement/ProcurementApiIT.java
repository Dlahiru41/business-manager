package com.bmp.procurement;

import static com.bmp.procurement.ProcurementApi.EGGS;
import static com.bmp.procurement.ProcurementApi.MILK;
import static com.bmp.procurement.ProcurementApi.OIL;
import static com.bmp.procurement.ProcurementApi.etag;
import static com.bmp.procurement.ProcurementApi.id;
import static com.bmp.procurement.ProcurementApi.json;
import static com.bmp.procurement.ProcurementApi.unit;
import static com.bmp.procurement.ProcurementApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.procurement.ProcurementTestApplication.StubReorderSuggestions;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.SupplierInvoicePosted;
import com.bmp.procurement.api.spi.ReorderSuggestion;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** Purchase orders, goods receipts and supplier invoices through the public API (PRO-01..PRO-07). */
@SpringBootTest(classes = ProcurementTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class ProcurementApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    StubReorderSuggestions reorder;

    /** A shop with a supplier, eggs and oil. */
    static final class Shop {
        final ProcurementApi api;
        final UUID supplier;
        final JsonNode small;
        final JsonNode medium;
        final JsonNode large;
        final JsonNode oil;

        Shop(MockMvcTester mvc, String name) {
            this.api = new ProcurementApi(mvc, name);
            this.supplier = api.supplier("Kegalle Poultry Farm", 14);
            JsonNode eggs = api.product(EGGS);
            this.small = variant(eggs, "EGG-RED-S");
            this.medium = variant(eggs, "EGG-RED-M");
            this.large = variant(eggs, "EGG-RED-L");
            this.oil = variant(api.product(OIL), "OIL");
        }

        String orderLine(JsonNode variant, String unitCode, String qty, String unitCost) {
            return "{\"variantId\": \"%s\", \"packagingUnitId\": \"%s\", \"qty\": \"%s\", \"unitCost\": \"%s\"}"
                    .formatted(id(variant), unit(variant, unitCode), qty, unitCost);
        }

        String order(String... lines) {
            return "{\"supplierId\": \"%s\", \"locationId\": \"%s\", \"lines\": [%s]}"
                    .formatted(supplier, api.location, String.join(", ", lines));
        }

        String receipt(String extra, String... lines) {
            return "{\"supplierId\": \"%s\", \"locationId\": \"%s\"%s, \"lines\": [%s]}"
                    .formatted(supplier, api.location, extra, String.join(", ", lines));
        }

        static String receiptLine(JsonNode variant, String unitCode, String qty, String unitCost, String extra) {
            return "{\"variantId\": \"%s\", \"packagingUnitId\": \"%s\", \"qty\": \"%s\", \"unitCost\": \"%s\"%s}"
                    .formatted(id(variant), unit(variant, unitCode), qty, unitCost, extra);
        }

        JsonNode posted(String body) {
            MvcTestResult created = api.post("/api/v1/goods-receipts", body);
            assertThat(created).hasStatus(201);
            MvcTestResult posted = api.action("/api/v1/goods-receipts/" + id(json(created)) + "/post");
            assertThat(posted).hasStatusOk();
            return json(posted);
        }

        JsonNode eggDelivery() {
            return posted(receipt(
                    "",
                    receiptLine(small, "piece", "300", "38.00", ""),
                    receiptLine(medium, "piece", "300", "42.00", ""),
                    receiptLine(large, "piece", "260", "46.50", "")));
        }
    }

    @Test
    void pro01Pro07OrdersAreNumberedPerTenantAndUseBuyableUnitsOnly() {
        Shop shop = new Shop(mvc, "Orders");
        ProcurementApi api = shop.api;

        MvcTestResult created = api.post(
                "/api/v1/purchase-orders",
                shop.order(
                        shop.orderLine(shop.medium, "tray", "10", "1275.00"),
                        shop.orderLine(shop.oil, "can", "2", "13800.00")));
        assertThat(created).hasStatus(201);
        JsonNode order = json(created);
        assertThat(order.path("number").asString()).isEqualTo("PO-000001");
        assertThat(order.path("status").asString()).isEqualTo("DRAFT");
        assertThat(order.path("total").path("amount").asString()).isEqualTo("40350.00");
        assertThat(order.path("lines").get(0).path("lineTotal").path("amount").asString())
                .isEqualTo("12750.00");
        assertThat(json(api.post("/api/v1/purchase-orders", shop.order(shop.orderLine(shop.small, "piece", "1", "38"))))
                        .path("number")
                        .asString())
                .isEqualTo("PO-000002");

        assertThatProblem(api.post(
                        "/api/v1/purchase-orders", shop.order(shop.orderLine(shop.oil, "bottle-750", "12", "900.00"))))
                .hasStatus(422)
                .hasCode("UNIT_NOT_PURCHASABLE");
        assertThatProblem(api.post(
                        "/api/v1/purchase-orders", shop.order("""
                        {"variantId": "%s", "packagingUnitId": "%s", "qty": "1", "unitCost": "1"}
                        """.formatted(UUID.randomUUID(), unit(shop.oil, "can")))))
                .hasStatus(422)
                .hasCode("UNKNOWN_VARIANT");
        assertThatProblem(api.post(
                        "/api/v1/purchase-orders", shop.order("""
                        {"variantId": "%s", "packagingUnitId": "%s", "qty": "1", "unitCost": "1"}
                        """.formatted(id(shop.oil), unit(shop.small, "tray")))))
                .hasStatus(422)
                .hasCode("UNKNOWN_PACKAGING_UNIT");
        assertThatProblem(api.post(
                        "/api/v1/purchase-orders", shop.order(shop.orderLine(shop.oil, "can", "0", "13800.00"))))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThatProblem(api.post("/api/v1/purchase-orders", """
                        {"supplierId": "%s", "locationId": "%s", "lines": [%s]}
                        """.formatted(
                                api.customer("Walk-in"), api.location, shop.orderLine(shop.oil, "can", "1", "1"))))
                .as("a customer is no supplier")
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");
        assertThatProblem(api.post("/api/v1/purchase-orders", """
                        {"supplierId": "%s", "locationId": "%s", "lines": [%s]}
                        """.formatted(
                                shop.supplier, UUID.randomUUID(), shop.orderLine(shop.oil, "can", "1", "1"))))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
    }

    @Test
    void pro01ADraftOrderIsChangedUnderItsETagThenSentAndCancelled() {
        Shop shop = new Shop(mvc, "Order lifecycle");
        ProcurementApi api = shop.api;
        MvcTestResult created =
                api.post("/api/v1/purchase-orders", shop.order(shop.orderLine(shop.medium, "tray", "10", "1275.00")));
        String path = "/api/v1/purchase-orders/" + id(json(created));

        assertThatProblem(api.client.patch(path, api.owner, "{\"notes\": \"x\"}", null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        MvcTestResult changed = api.patch(
                path,
                """
                {"expectedOn": "2026-11-25", "notes": "Morning delivery", "lines": [%s, %s]}
                """.formatted(
                                shop.orderLine(shop.medium, "tray", "12", "1275.00"),
                                shop.orderLine(shop.large, "tray", "5", "1395.00")),
                etag(created));
        assertThat(changed).hasStatusOk();
        assertThat(json(changed).path("lines")).hasSize(2);
        assertThat(json(changed).path("expectedOn").asString()).isEqualTo("2026-11-25");
        assertThatProblem(api.patch(path, "{\"notes\": \"stale\"}", etag(created)))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");

        JsonNode sent = json(api.action(path + "/send"));
        assertThat(sent.path("status").asString()).isEqualTo("SENT");
        assertThat(sent.path("sentAt").asString()).isNotBlank();
        assertThatProblem(api.patch(path, "{\"notes\": \"late\"}", etag(api.get(path))))
                .hasStatus(409)
                .hasCode("PURCHASE_ORDER_STATE_INVALID");
        assertThat(json(api.get("/api/v1/purchase-orders?status=SENT")).path("items"))
                .extracting(o -> o.path("id").asString())
                .containsExactly(sent.path("id").asString());
        JsonNode cancelled = json(api.action(path + "/cancel"));
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThatProblem(api.action(path + "/cancel")).hasStatus(409).hasCode("PURCHASE_ORDER_STATE_INVALID");
        assertThatProblem(api.get("/api/v1/purchase-orders/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PURCHASE_ORDER_NOT_FOUND");
    }

    @Test
    void pro02Pro03Pro04TheEggDeliveryIsPostedAgainstTheOrderAndPublished() {
        Shop shop = new Shop(mvc, "Egg delivery");
        ProcurementApi api = shop.api;
        JsonNode order = json(api.post(
                "/api/v1/purchase-orders",
                shop.order(
                        shop.orderLine(shop.small, "piece", "300", "38.00"),
                        shop.orderLine(shop.medium, "piece", "300", "42.00"),
                        shop.orderLine(shop.large, "piece", "300", "46.50"))));
        String orderPath = "/api/v1/purchase-orders/" + id(order);
        String early = shop.receipt(
                ", \"purchaseOrderId\": \"" + id(order) + "\"",
                Shop.receiptLine(shop.small, "piece", "1", "38.00", ""));
        assertThatProblem(api.post("/api/v1/goods-receipts", early))
                .as("a draft order receives nothing")
                .hasStatus(409)
                .hasCode("PURCHASE_ORDER_STATE_INVALID");
        api.action(orderPath + "/send");
        JsonNode lines = order.path("lines");
        String withOrder = ", \"purchaseOrderLineId\": \"%s\"";

        MvcTestResult created = api.post(
                "/api/v1/goods-receipts",
                shop.receipt(
                        ", \"purchaseOrderId\": \"" + id(order) + "\", \"receivedAt\": \"2026-11-23T07:30:00Z\"",
                        Shop.receiptLine(shop.small, "piece", "300", "38.00", withOrder.formatted(id(lines.get(0)))),
                        Shop.receiptLine(shop.medium, "piece", "300", "42.00", withOrder.formatted(id(lines.get(1)))),
                        Shop.receiptLine(shop.large, "piece", "260", "46.50", withOrder.formatted(id(lines.get(2))))));
        assertThat(created).hasStatus(201);
        JsonNode draft = json(created);
        assertThat(draft.path("number").asString()).isEqualTo("GRN-000001");
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("lines").get(2).path("qtyBase").asString()).isEqualTo("260");
        assertThat(draft.path("total").path("amount").asString()).isEqualTo("36090.00");
        assertThat(events.stream(GoodsReceived.class).filter(e -> e.tenantId().equals(api.tenant)))
                .as("a draft publishes nothing")
                .isEmpty();

        String path = "/api/v1/goods-receipts/" + id(draft);
        JsonNode posted = json(api.action(path + "/post"));
        assertThat(posted.path("status").asString()).isEqualTo("POSTED");
        assertThat(posted.path("postedAt").asString()).isNotBlank();
        assertThat(json(api.get(orderPath)).path("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(json(api.get(orderPath))
                        .path("lines")
                        .get(2)
                        .path("receivedQty")
                        .asString())
                .isEqualTo("260");
        GoodsReceived event = events.stream(GoodsReceived.class)
                .filter(e -> e.tenantId().equals(api.tenant))
                .toList()
                .getFirst();
        assertThat(event.receiptId().toString()).isEqualTo(id(draft));
        assertThat(event.number()).isEqualTo("GRN-000001");
        assertThat(event.supplierId()).isEqualTo(PartyId.of(shop.supplier));
        assertThat(event.locationId()).isEqualTo(LocationId.of(api.location));
        assertThat(event.receivedAt()).hasToString("2026-11-23T07:30:00Z");
        assertThat(event.total()).isEqualByComparingTo("36090.00");
        assertThat(event.lines())
                .extracting(l -> l.qtyBase().stripTrailingZeros().toPlainString())
                .containsExactly("300", "300", "260");
        assertThat(event.lines().get(2).unitCostBase()).isEqualByComparingTo("46.50");
        assertThat(event.lines().get(2).variantId()).isEqualTo(VariantId.of(UUID.fromString(id(shop.large))));

        assertThatProblem(api.action(path + "/post")).hasStatus(409).hasCode("GOODS_RECEIPT_STATE_INVALID");
        assertThatProblem(api.patch(path, "{\"notes\": \"late\"}", etag(api.get(path))))
                .as("a posted receipt never changes")
                .hasStatus(409)
                .hasCode("GOODS_RECEIPT_STATE_INVALID");
        shop.posted(shop.receipt(
                ", \"purchaseOrderId\": \"" + id(order) + "\"",
                Shop.receiptLine(shop.large, "piece", "40", "46.50", withOrder.formatted(id(lines.get(2))))));
        assertThat(json(api.get(orderPath)).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(json(api.get("/api/v1/goods-receipts?purchaseOrderId=" + id(order)))
                        .path("items"))
                .hasSize(2);
    }

    @Test
    void pro02Pro03WalkInDeliveriesInTraysOrWithExpiryAndOrderMismatches() {
        Shop shop = new Shop(mvc, "Walk-in");
        ProcurementApi api = shop.api;
        JsonNode trays = shop.posted(shop.receipt("", Shop.receiptLine(shop.medium, "tray", "10", "1275.00", "")));
        assertThat(trays.path("purchaseOrderId").isMissingNode()
                        || trays.path("purchaseOrderId").isNull())
                .isTrue();
        assertThat(trays.path("lines").get(0).path("qtyBase").asString()).isEqualTo("300");
        assertThat(trays.path("lines").get(0).path("unitCostBase").asString()).isEqualTo("42.5");

        JsonNode milk = variant(api.product(MILK), "MILK");
        assertThatProblem(api.post(
                        "/api/v1/goods-receipts",
                        shop.receipt("", Shop.receiptLine(milk, "piece", "24", "180.00", ""))))
                .hasStatus(422)
                .hasCode("EXPIRY_REQUIRED");
        JsonNode lot = shop.posted(shop.receipt(
                "",
                Shop.receiptLine(
                        milk, "piece", "24", "180.00", ", \"batchCode\": \"LOT-7\", \"expiresOn\": \"2026-12-01\"")));
        assertThat(lot.path("lines").get(0).path("batchCode").asString()).isEqualTo("LOT-7");
        assertThat(lot.path("lines").get(0).path("expiresOn").asString()).isEqualTo("2026-12-01");

        JsonNode order = json(
                api.post("/api/v1/purchase-orders", shop.order(shop.orderLine(shop.small, "tray", "10", "1140.00"))));
        api.action("/api/v1/purchase-orders/" + id(order) + "/send");
        String orderLine =
                ", \"purchaseOrderLineId\": \"" + id(order.path("lines").get(0)) + "\"";
        assertThatProblem(api.post(
                        "/api/v1/goods-receipts",
                        shop.receipt(
                                ", \"purchaseOrderId\": \"" + id(order) + "\"",
                                Shop.receiptLine(shop.large, "tray", "10", "1395.00", orderLine))))
                .as("the order line is for small eggs")
                .hasStatus(422)
                .hasCode("PURCHASE_ORDER_MISMATCH");
        assertThatProblem(api.post(
                        "/api/v1/goods-receipts",
                        shop.receipt("", Shop.receiptLine(shop.small, "tray", "10", "1140.00", orderLine))))
                .as("an order line needs its order")
                .hasStatus(422)
                .hasCode("PURCHASE_ORDER_MISMATCH");
        UUID other = api.supplier("Other farm", 0);
        assertThatProblem(api.post("/api/v1/goods-receipts", """
                        {"supplierId": "%s", "locationId": "%s", "purchaseOrderId": "%s", "lines": [%s]}
                        """.formatted(
                                other, api.location, id(order), Shop.receiptLine(shop.small, "tray", "1", "1", ""))))
                .hasStatus(422)
                .hasCode("PURCHASE_ORDER_MISMATCH");

        MvcTestResult draft = api.post(
                "/api/v1/goods-receipts", shop.receipt("", Shop.receiptLine(shop.small, "tray", "1", "1140.00", "")));
        String path = "/api/v1/goods-receipts/" + id(json(draft));
        MvcTestResult changed =
                api.patch(path, """
                {"receivedAt": "2026-11-23T06:00:00Z", "notes": "Two cracked", "lines": [%s]}
                """.formatted(Shop.receiptLine(shop.small, "tray", "2", "1140.00", "")), etag(draft));
        assertThat(json(changed).path("total").path("amount").asString()).isEqualTo("2280.00");
        assertThat(json(changed).path("notes").asString()).isEqualTo("Two cracked");
        assertThat(json(api.action(path + "/cancel")).path("status").asString()).isEqualTo("CANCELLED");
        assertThatProblem(api.action(path + "/post")).hasStatus(409).hasCode("GOODS_RECEIPT_STATE_INVALID");
        assertThatProblem(api.get("/api/v1/goods-receipts/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("GOODS_RECEIPT_NOT_FOUND");
    }

    @Test
    void pro02AReturnNeedsTheCorrectPermissionAndSendsBackNoMoreThanArrived() {
        Shop shop = new Shop(mvc, "Returns");
        ProcurementApi api = shop.api;
        JsonNode delivered = shop.eggDelivery();
        String corrects = ", \"correctsReceiptId\": \"" + id(delivered) + "\"";
        String back100 = shop.receipt(corrects, Shop.receiptLine(shop.small, "piece", "-100", "38.00", ""));

        assertThat(api.post(
                        "/api/v1/goods-receipts",
                        api.storekeeper,
                        shop.receipt("", Shop.receiptLine(shop.small, "piece", "5", "38.00", ""))))
                .as("a storekeeper receives")
                .hasStatus(201);
        assertThatProblem(api.post("/api/v1/goods-receipts", api.storekeeper, back100))
                .as("but returns need procurement:correct")
                .hasStatus(403)
                .hasCode("CORRECTION_PERMISSION_REQUIRED");
        assertThatProblem(api.post(
                        "/api/v1/goods-receipts",
                        shop.receipt("", Shop.receiptLine(shop.small, "piece", "-1", "38.00", ""))))
                .hasStatus(422)
                .hasCode("CORRECTION_TARGET_INVALID");
        assertThatProblem(api.post(
                        "/api/v1/goods-receipts",
                        shop.receipt(
                                corrects,
                                Shop.receiptLine(shop.small, "piece", "-1", "38.00", ""),
                                Shop.receiptLine(shop.medium, "piece", "1", "42.00", ""))))
                .hasStatus(422)
                .hasCode("MIXED_LINE_SIGNS");

        JsonNode returned = shop.posted(back100);
        assertThat(returned.path("total").path("amount").asString()).isEqualTo("-3800.00");
        assertThat(returned.path("correctsReceiptId").asString()).isEqualTo(id(delivered));
        assertThat(events.stream(GoodsReceived.class)
                        .filter(e -> e.receiptId().toString().equals(id(returned))))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.correctsReceiptId()).hasToString(id(delivered));
                    assertThat(e.lines().getFirst().qtyBase()).isEqualByComparingTo(new BigDecimal("-100"));
                });
        assertThatProblem(api.post(
                        "/api/v1/goods-receipts",
                        shop.receipt(corrects, Shop.receiptLine(shop.small, "piece", "-201", "38.00", ""))))
                .as("300 arrived, 100 went back")
                .hasStatus(422)
                .hasCode("CORRECTION_TARGET_INVALID");
        MvcTestResult first = api.post(
                "/api/v1/goods-receipts",
                shop.receipt(corrects, Shop.receiptLine(shop.small, "piece", "-150", "38.00", "")));
        MvcTestResult second = api.post(
                "/api/v1/goods-receipts",
                shop.receipt(corrects, Shop.receiptLine(shop.small, "piece", "-150", "38.00", "")));
        assertThat(api.action("/api/v1/goods-receipts/" + id(json(first)) + "/post"))
                .hasStatusOk();
        assertThatProblem(api.action("/api/v1/goods-receipts/" + id(json(second)) + "/post"))
                .as("checked again when posting")
                .hasStatus(422)
                .hasCode("CORRECTION_TARGET_INVALID");
    }

    @Test
    void pro05AnInvoiceMatchesPostedReceiptsAndCarriesThePriceVariance() {
        Shop shop = new Shop(mvc, "Invoices");
        ProcurementApi api = shop.api;
        JsonNode eggs = shop.eggDelivery();
        JsonNode trays = shop.posted(shop.receipt("", Shop.receiptLine(shop.medium, "tray", "10", "1275.00", "")));
        String invoice = """
                {"supplierId": "%s", "supplierRef": "INV-778", "invoiceDate": "2026-11-23", "subtotal": "%s",
                 "receiptIds": ["%s", "%s"]}
                """;

        MvcTestResult created = api.post(
                "/api/v1/supplier-invoices", invoice.formatted(shop.supplier, "49000.00", id(eggs), id(trays)));
        assertThat(created).hasStatus(201);
        JsonNode draft = json(created);
        assertThat(draft.path("dueDate").asString())
                .as("14 days of payment terms")
                .isEqualTo("2026-12-07");
        assertThat(draft.path("receiptValue").path("amount").asString()).isEqualTo("48840.00");
        assertThat(draft.path("priceVariance").path("amount").asString()).isEqualTo("160.00");
        assertThat(draft.path("tax").path("amount").asString()).isEqualTo("0.00");
        assertThat(draft.path("receipts"))
                .extracting(r -> r.path("receiptNumber").asString())
                .containsExactly("GRN-000001", "GRN-000002");
        assertThatProblem(api.post(
                        "/api/v1/supplier-invoices", invoice.formatted(shop.supplier, "1.00", id(eggs), id(trays))))
                .as("the supplier's reference is unique")
                .hasStatus(409)
                .hasCode("SUPPLIER_INVOICE_REF_TAKEN");
        MvcTestResult rival = api.post("/api/v1/supplier-invoices", """
                {"supplierId": "%s", "supplierRef": "INV-779", "invoiceDate": "2026-11-23", "dueDate": "2026-11-30",
                 "subtotal": "12750.00", "tax": "0.00", "receiptIds": ["%s"]}
                """.formatted(shop.supplier, id(trays)));
        assertThat(json(rival).path("dueDate").asString()).isEqualTo("2026-11-30");

        String path = "/api/v1/supplier-invoices/" + id(draft);
        JsonNode posted = json(api.action(path + "/post"));
        assertThat(posted.path("status").asString()).isEqualTo("POSTED");
        assertThat(json(api.get("/api/v1/goods-receipts/" + id(eggs)))
                        .path("invoiced")
                        .asBoolean())
                .isTrue();
        assertThat(json(api.get("/api/v1/goods-receipts?invoiced=false")).path("items"))
                .isEmpty();
        assertThat(events.stream(SupplierInvoicePosted.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.priceVariance()).isEqualByComparingTo("160.00");
                    assertThat(e.receiptValue()).isEqualByComparingTo("48840.00");
                    assertThat(e.receiptIds()).hasSize(2);
                    assertThat(e.dueDate()).hasToString("2026-12-07");
                });
        assertThatProblem(api.action(path + "/post")).hasStatus(409).hasCode("INVOICE_STATE_INVALID");
        assertThatProblem(api.action("/api/v1/supplier-invoices/" + id(json(rival)) + "/post"))
                .as("the trays were invoiced in the meantime")
                .hasStatus(409)
                .hasCode("RECEIPT_ALREADY_INVOICED");
        assertThatProblem(api.post("/api/v1/supplier-invoices", """
                        {"supplierId": "%s", "supplierRef": "INV-780", "invoiceDate": "2026-11-23", "subtotal": "1.00",
                         "receiptIds": ["%s"]}
                        """.formatted(shop.supplier, id(eggs))))
                .hasStatus(409)
                .hasCode("RECEIPT_ALREADY_INVOICED");
        assertThat(json(api.get("/api/v1/supplier-invoices?status=POSTED&supplierId=" + shop.supplier))
                        .path("items"))
                .hasSize(1);
        assertThatProblem(api.get("/api/v1/supplier-invoices/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("SUPPLIER_INVOICE_NOT_FOUND");
    }

    @Test
    void pro05OnlyPostedReceiptsOfTheSameSupplierAreInvoiced() {
        Shop shop = new Shop(mvc, "Invoice matching");
        ProcurementApi api = shop.api;
        String draft = id(json(api.post(
                "/api/v1/goods-receipts", shop.receipt("", Shop.receiptLine(shop.small, "piece", "10", "38.00", "")))));
        UUID other = api.supplier("Other farm", 0);
        JsonNode theirs = shop.posted(
                """
                {"supplierId": "%s", "locationId": "%s", "lines": [%s]}
                """.formatted(other, api.location, Shop.receiptLine(shop.small, "piece", "10", "38.00", "")));
        String invoice = """
                {"supplierId": "%s", "supplierRef": "R-1", "invoiceDate": "2026-11-23", "subtotal": "380.00",
                 "receiptIds": ["%s"]}
                """;

        assertThatProblem(api.post("/api/v1/supplier-invoices", invoice.formatted(shop.supplier, draft)))
                .hasStatus(422)
                .hasCode("RECEIPT_NOT_POSTED");
        assertThatProblem(api.post("/api/v1/supplier-invoices", invoice.formatted(shop.supplier, id(theirs))))
                .hasStatus(422)
                .hasCode("SUPPLIER_MISMATCH");
        assertThatProblem(api.post("/api/v1/supplier-invoices", invoice.formatted(shop.supplier, UUID.randomUUID())))
                .hasStatus(422)
                .hasCode("UNKNOWN_RECEIPT");
        assertThatProblem(api.post("/api/v1/supplier-invoices", invoice.formatted(api.customer("Shopper"), id(theirs))))
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");
    }

    @Test
    void pro06TheReorderListBecomesOneDraftOrderPerSupplierAndLocation() {
        Shop shop = new Shop(mvc, "Reorder");
        ProcurementApi api = shop.api;
        UUID other = api.supplier("Oil mills", 30);
        LocationId location = LocationId.of(api.location);
        reorder.set(
                api.tenant,
                List.of(
                        suggestion(shop.small, location, "400", shop.supplier, "38.5"),
                        suggestion(shop.large, location, "100", shop.supplier, null),
                        suggestion(shop.oil, location, "30000", other, "0.705"),
                        suggestion(shop.medium, location, "60", null, "42"),
                        suggestion(shop.small, LocationId.of(UUID.randomUUID()), "10", shop.supplier, "38"),
                        suggestion(shop.large, location, "10", api.customer("Walk-in"), "46")));

        MvcTestResult result = api.post("/api/v1/purchase-orders:from-reorder-list", "{}");
        assertThat(result).hasStatus(201);
        JsonNode orders = json(result).path("orders");
        assertThat(orders).hasSize(2);
        assertThat(json(result).path("skippedVariantIds"))
                .extracting(JsonNode::asString)
                .as("no preferred supplier, an unknown location, a customer as supplier")
                .containsExactly(id(shop.medium), id(shop.small), id(shop.large));
        JsonNode eggs = orders.get(0);
        assertThat(eggs.path("supplierId").asString()).isEqualTo(shop.supplier.toString());
        assertThat(eggs.path("status").asString()).isEqualTo("DRAFT");
        assertThat(eggs.path("lines")).extracting(l -> l.path("qty").asString()).containsExactly("400", "100");
        assertThat(eggs.path("lines"))
                .extracting(l -> l.path("unitCost").asString())
                .containsExactly("38.5", "0");
        JsonNode oil = orders.get(1).path("lines").get(0);
        assertThat(oil.path("packagingUnitId").asString()).isEqualTo(unit(shop.oil, "can"));
        assertThat(oil.path("qty").asString())
                .as("30 l in cans of 19,565.28 ml, rounded up")
                .isEqualTo("2");
        assertThat(oil.path("unitCost").asString()).isEqualTo("13793.5224");

        assertThatProblem(api.post(
                        "/api/v1/purchase-orders:from-reorder-list", "{\"locationId\": \"" + UUID.randomUUID() + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
        reorder.set(api.tenant, List.of());
        assertThat(json(api.post(
                                "/api/v1/purchase-orders:from-reorder-list",
                                "{\"locationId\": \"" + api.location + "\"}"))
                        .path("orders"))
                .isEmpty();
    }

    @Test
    void pagesAreNewestFirstWithCursorsAndRetriesReplayTheFirstAnswer() {
        Shop shop = new Shop(mvc, "Pages");
        ProcurementApi api = shop.api;
        String body = shop.receipt("", Shop.receiptLine(shop.small, "piece", "10", "38.00", ""));
        String key = UUID.randomUUID().toString();
        MvcTestResult first = api.client.post("/api/v1/goods-receipts", api.owner, body, key);
        MvcTestResult retry = api.client.post("/api/v1/goods-receipts", api.owner, body, key);
        assertThat(id(json(retry))).isEqualTo(id(json(first)));
        api.post("/api/v1/goods-receipts", body);

        JsonNode page = json(api.get("/api/v1/goods-receipts?limit=1"));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("number").asString()).isEqualTo("GRN-000002");
        assertThat(page.path("page").path("hasMore").asBoolean()).isTrue();
        JsonNode next = json(api.get("/api/v1/goods-receipts?limit=1&cursor="
                + page.path("page").path("nextCursor").asString()));
        assertThat(next.path("items").get(0).path("number").asString()).isEqualTo("GRN-000001");
        assertThat(next.path("page").path("hasMore").asBoolean()).isFalse();
        assertThat(json(api.get("/api/v1/goods-receipts?status=DRAFT&supplierId=" + shop.supplier))
                        .path("items"))
                .hasSize(2);
        assertThat(json(api.get("/api/v1/purchase-orders")).path("items")).isEmpty();
        assertThat(json(api.get("/api/v1/supplier-invoices")).path("items")).isEmpty();
        assertThatProblem(api.get("/api/v1/goods-receipts?cursor=bm90LWEtdXVpZA"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    private static ReorderSuggestion suggestion(
            JsonNode variant, LocationId location, String qty, @Nullable UUID supplier, @Nullable String cost) {
        return new ReorderSuggestion(
                VariantId.of(UUID.fromString(id(variant))),
                location,
                new BigDecimal(qty),
                supplier == null ? null : PartyId.of(supplier),
                cost == null ? null : new BigDecimal(cost));
    }
}
