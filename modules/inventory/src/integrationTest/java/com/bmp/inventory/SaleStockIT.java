package com.bmp.inventory;

import static com.bmp.inventory.GoodsReceiptStockIT.line;
import static com.bmp.inventory.GoodsReceiptStockIT.receipt;
import static com.bmp.inventory.InventoryApi.EGGS;
import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.InventoryTestApplication.StockEventRecorder;
import com.bmp.inventory.api.events.StockIssued;
import com.bmp.inventory.application.command.SaleStockHandler;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.ReturnedLine;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleLine;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleTender;
import com.bmp.sales.api.events.SaleTotals;
import com.bmp.sales.api.events.SaleVoided;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bills in stock (T03-04, INV-05..INV-07, SAL-11): SALE movements per line at current cost, restocked returns at their
 * issue cost, voids reversing what is still out, each once however often the event is delivered.
 */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SaleStockIT {

    static final Instant SOLD = Instant.parse("2026-11-23T10:15:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    StockEventRecorder recorded;

    @Autowired
    SaleStockHandler handler;

    @Autowired
    JsonMapper json;

    /** A shop with 300 large eggs at 38.00 each. */
    private record Shop(InventoryApi api, JsonNode large, JsonNode oil) {}

    private Shop shop(String name) {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, name);
        JsonNode large = variant(api.product(EGGS), "EGG-RED-L");
        JsonNode oil = variant(api.product(OIL), "OIL");
        GoodsReceived delivery = receipt(api, null, List.of(line(large, "piece", "300", "1", "38.00", null, null)));
        publish(api, delivery);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(onHand(api, large)).isEqualByComparingTo("300"));
        return new Shop(api, large, oil);
    }

    private void publish(InventoryApi api, Object event) {
        TenantContext.runWith(
                api.tenant,
                () -> new TransactionTemplate(transactions).executeWithoutResult(s -> publisher.publishEvent(event)));
    }

    private static SaleLine sold(JsonNode variant, String unitCode, String qtyBase, String stockState) {
        BigDecimal qty = new BigDecimal(qtyBase);
        return new SaleLine(
                UUID.randomUUID(),
                VariantId.of(id(variant)),
                PackagingUnitId.of(UUID.fromString(unit(variant, unitCode))),
                variant.path("sku").asString(),
                qty,
                qty,
                stockState,
                false,
                new BigDecimal("55.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                qty.multiply(new BigDecimal("55.00")));
    }

    private static SaleCompleted completed(InventoryApi api, List<SaleLine> lines) {
        UUID bill = UUID.randomUUID();
        BigDecimal total = lines.stream().map(SaleLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SaleCompleted(
                UUID.randomUUID(),
                api.tenant,
                SOLD,
                bill,
                1,
                EventMetadata.empty(),
                bill,
                "R1-000001",
                "RETAIL",
                null,
                LocationId.of(api.location),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 11, 23),
                SOLD,
                "LKR",
                false,
                List.of(),
                lines,
                new SaleTotals(total, BigDecimal.ZERO, BigDecimal.ZERO, total),
                List.of(new SaleTender(UUID.randomUUID(), "CASH", total, total, BigDecimal.ZERO, null)));
    }

    private static SaleReturned returned(InventoryApi api, SaleCompleted sale, List<ReturnedLine> lines) {
        UUID note = UUID.randomUUID();
        BigDecimal total = lines.stream().map(ReturnedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SaleReturned(
                UUID.randomUUID(),
                api.tenant,
                SOLD.plusSeconds(3600),
                note,
                1,
                EventMetadata.empty(),
                note,
                "CN-000001",
                sale.billId(),
                sale.number(),
                null,
                sale.locationId(),
                sale.registerId(),
                "LKR",
                "Cracked",
                total,
                BigDecimal.ZERO,
                lines,
                List.of(new RefundTender(UUID.randomUUID(), "CASH", total, null, null)),
                SOLD.plusSeconds(3600));
    }

    private static ReturnedLine back(SaleLine line, String qty, boolean restock) {
        BigDecimal quantity = new BigDecimal(qty);
        return new ReturnedLine(
                line.lineId(),
                line.variantId(),
                quantity,
                quantity,
                line.stockState(),
                quantity.multiply(line.unitPrice()),
                BigDecimal.ZERO,
                restock);
    }

    private static SaleVoided voided(InventoryApi api, SaleCompleted sale) {
        return new SaleVoided(
                UUID.randomUUID(),
                api.tenant,
                SOLD.plusSeconds(600),
                sale.billId(),
                2,
                EventMetadata.empty(),
                sale.billId(),
                sale.number(),
                null,
                sale.locationId(),
                sale.registerId(),
                "LKR",
                "Wrong customer",
                UUID.randomUUID(),
                sale.totals().grandTotal(),
                sale.tenders(),
                SOLD.plusSeconds(600));
    }

    private List<Map<String, Object>> movements(InventoryApi api, UUID line, int expected) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(movements(api, line)).hasSize(expected));
        return movements(api, line);
    }

    private List<Map<String, Object>> movements(InventoryApi api, UUID line) {
        return api.sql(j -> j.sql("""
                        SELECT type, source_type, source_id, qty_base, unit_cost, cost_amount, stock_state, flags, occurred_at
                          FROM inventory.stock_movement WHERE source_line_id = :l ORDER BY recorded_at, id""").param("l", line).query().listOfRows());
    }

    private static BigDecimal onHand(InventoryApi api, JsonNode variant) {
        return api.sql(j -> j.sql("SELECT coalesce(sum(on_hand), 0) FROM inventory.stock_balance WHERE variant_id = :v")
                .param("v", id(variant))
                .query(BigDecimal.class)
                .single());
    }

    private static String text(Object value) {
        return ((BigDecimal) value).toPlainString();
    }

    private List<StockIssued> issued(InventoryApi api, UUID billId, int expected) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(recorded.issued(api.tenant).stream()
                                .filter(e -> e.billId().equals(billId)))
                        .hasSize(expected));
        return recorded.issued(api.tenant).stream()
                .filter(e -> e.billId().equals(billId))
                .toList();
    }

    @Test
    void inv05Inv07ASaleIssuesEveryLineAtCurrentCostOnceAndMayGoBelowZero() {
        Shop shop = shop("Sale issue");
        SaleLine eggs = sold(shop.large(), "piece", "10", "DEFAULT");
        SaleLine oil = sold(shop.oil(), "bottle-750", "750", "OPEN");
        SaleCompleted sale = completed(shop.api(), List.of(eggs, oil));

        publish(shop.api(), sale);
        List<Map<String, Object>> egg = movements(shop.api(), eggs.lineId(), 1);
        publish(shop.api(), sale);

        assertThat(egg.getFirst())
                .containsEntry("type", "SALE")
                .containsEntry("source_type", "SALE")
                .containsEntry("source_id", sale.billId())
                .containsEntry("stock_state", "DEFAULT");
        assertThat(text(egg.getFirst().get("qty_base"))).isEqualTo("-10.000000");
        assertThat(text(egg.getFirst().get("cost_amount"))).isEqualTo("-380.0000");
        assertThat(egg.getFirst().get("occurred_at").toString()).startsWith("2026-11-23 10:15");
        List<Map<String, Object>> bottle = movements(shop.api(), oil.lineId(), 1);
        assertThat(bottle.getFirst()).containsEntry("stock_state", "OPEN");
        assertThat(bottle.getFirst().get("flags").toString())
                .as("INV-07: the sale went through without stock")
                .contains("NEGATIVE_STOCK");
        assertThat(issued(shop.api(), sale.billId(), 1).getFirst()).satisfies(e -> {
            assertThat(e.sourceType()).isEqualTo("SALE");
            assertThat(e.sourceId()).isEqualTo(sale.billId());
            assertThat(e.lines())
                    .extracting(l -> l.sourceLineId())
                    .containsExactlyInAnyOrder(eggs.lineId(), oil.lineId());
            EventContract.assertConforms(json.writeValueAsString(e), "StockIssued");
        });
        assertThat(onHand(shop.api(), shop.large())).isEqualByComparingTo("290");
        assertThat(movements(shop.api(), eggs.lineId()))
                .as("the second delivery of the same event changed nothing")
                .hasSize(1);
    }

    @Test
    void aReturnRestocksAtTheIssueCostAndLinesNotRestockedMoveNothing() {
        Shop shop = shop("Sale return");
        SaleLine eggs = sold(shop.large(), "piece", "10", "DEFAULT");
        SaleLine more = sold(shop.large(), "piece", "5", "DEFAULT");
        SaleCompleted sale = completed(shop.api(), List.of(eggs, more));
        publish(shop.api(), sale);
        movements(shop.api(), more.lineId(), 1);
        GoodsReceived dearer =
                receipt(shop.api(), null, List.of(line(shop.large(), "piece", "100", "1", "50.00", null, null)));
        publish(shop.api(), dearer);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> assertThat(onHand(shop.api(), shop.large())).isEqualByComparingTo("385"));

        SaleReturned back = returned(shop.api(), sale, List.of(back(eggs, "2", true), back(more, "5", false)));
        publish(shop.api(), back);
        List<Map<String, Object>> egg = movements(shop.api(), eggs.lineId(), 2);

        assertThat(egg.get(1))
                .containsEntry("type", "SALE_RETURN")
                .containsEntry("source_type", "SALE_RETURN")
                .containsEntry("source_id", back.creditNoteId());
        assertThat(text(egg.get(1).get("qty_base"))).isEqualTo("2.000000");
        assertThat(text(egg.get(1).get("cost_amount")))
                .as("at the 38.00 the eggs left with, not today's average")
                .isEqualTo("76.0000");
        assertThat(movements(shop.api(), more.lineId()))
                .as("cracked eggs stay out: their cost stays with the sale")
                .hasSize(1);
        assertThat(onHand(shop.api(), shop.large())).isEqualByComparingTo("387");
        assertThat(issued(shop.api(), sale.billId(), 2))
                .filteredOn(e -> e.sourceType().equals("SALE_RETURN"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.sourceId()).isEqualTo(back.creditNoteId());
                    EventContract.assertConforms(json.writeValueAsString(e), "StockIssued");
                });
        assertThat(shop.api()
                        .inTenant(() -> handler.returned(new SaleStockHandler.Return(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                sale.billId(),
                                shop.api().location,
                                SOLD,
                                "LKR",
                                List.of()))))
                .as("a return that restocks nothing moves nothing")
                .isEmpty();
    }

    @Test
    void aVoidReversesWhatIsStillOutAtIssueCostAndNeverTwice() {
        Shop shop = shop("Sale void");
        SaleLine eggs = sold(shop.large(), "piece", "10", "DEFAULT");
        SaleCompleted sale = completed(shop.api(), List.of(eggs));
        publish(shop.api(), sale);
        movements(shop.api(), eggs.lineId(), 1);
        publish(shop.api(), returned(shop.api(), sale, List.of(back(eggs, "3", true))));
        movements(shop.api(), eggs.lineId(), 2);

        SaleVoided voided = voided(shop.api(), sale);
        publish(shop.api(), voided);
        List<Map<String, Object>> egg = movements(shop.api(), eggs.lineId(), 3);
        publish(shop.api(), voided(shop.api(), sale));

        assertThat(egg.get(2))
                .containsEntry("type", "SALE_VOID")
                .containsEntry("source_type", "SALE_VOID")
                .containsEntry("source_id", sale.billId());
        assertThat(text(egg.get(2).get("qty_base")))
                .as("sales voids bills without returns; should one come, only what is out comes back")
                .isEqualTo("7.000000");
        assertThat(text(egg.get(2).get("cost_amount"))).isEqualTo("266.0000");
        assertThat(onHand(shop.api(), shop.large())).isEqualByComparingTo("300");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                        SELECT count(*) FROM platform.event_publication
                                         WHERE completion_date IS NULL AND listener_id LIKE '%SalesListener%'
                                           AND serialized_event LIKE :bill""")
                                .param("bill", "%" + sale.billId() + "%")
                                .query(Long.class)
                                .single())
                        .as("the second void was delivered and did nothing")
                        .isZero());
        assertThat(movements(shop.api(), eggs.lineId())).hasSize(3);
        assertThat(issued(shop.api(), sale.billId(), 3))
                .extracting(StockIssued::sourceType)
                .containsExactly("SALE", "SALE_RETURN", "SALE_VOID");
    }

    @Test
    void aReturnOrVoidBeforeItsSaleIsBookedWaitsForIt() {
        Shop shop = shop("Return first");
        SaleLine eggs = sold(shop.large(), "piece", "10", "DEFAULT");
        SaleCompleted sale = completed(shop.api(), List.of(eggs));
        SaleReturned back = returned(shop.api(), sale, List.of(back(eggs, "2", true)));
        SaleStockHandler.Return early = new SaleStockHandler.Return(
                back.eventId(),
                back.creditNoteId(),
                sale.billId(),
                shop.api().location,
                back.returnedAt(),
                "LKR",
                List.of(new SaleStockHandler.RestockedLine(eggs.lineId(), new BigDecimal("2"))));
        SaleStockHandler.Voided voidFirst =
                new SaleStockHandler.Voided(UUID.randomUUID(), sale.billId(), shop.api().location, SOLD, "LKR");

        assertThatThrownBy(() -> shop.api().inTenant(() -> handler.returned(early)))
                .isInstanceOf(OutOfOrderEventException.class);
        assertThatThrownBy(() -> shop.api().inTenant(() -> handler.voided(voidFirst)))
                .isInstanceOf(OutOfOrderEventException.class);
        assertThat(movements(shop.api(), eggs.lineId())).isEmpty();

        publish(shop.api(), sale);
        movements(shop.api(), eggs.lineId(), 1);
        UUID nothing = UUID.randomUUID();
        assertThat(shop.api()
                        .inTenant(() -> handler.sold(new SaleStockHandler.Sale(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                shop.api().location,
                                SOLD,
                                "LKR",
                                List.of(new SaleStockHandler.SoldLine(
                                        nothing, id(shop.large()), "DEFAULT", BigDecimal.ZERO))))))
                .as("a line without a base quantity issues nothing")
                .isEmpty();
        assertThat(movements(shop.api(), nothing)).isEmpty();
        assertThat(shop.api().inTenant(() -> handler.returned(early)))
                .singleElement()
                .satisfies(m -> assertThat(m.costAmount()).isEqualByComparingTo("76.0000"));
    }
}
