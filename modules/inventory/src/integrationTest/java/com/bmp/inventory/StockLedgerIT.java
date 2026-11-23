package com.bmp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.inventory.api.events.StockLevelLow;
import com.bmp.inventory.application.StockLedger;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.ReorderPolicy;
import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.model.StockUnit;
import com.bmp.inventory.domain.port.BatchStore;
import com.bmp.inventory.domain.port.ReorderPolicyStore;
import com.bmp.inventory.domain.port.StockItemStore;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T02-04: the ledger posts movements, keeps balances, costs, batches and reorder alerts, and survives contention. */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class StockLedgerIT {

    static final Instant AT = Instant.parse("2026-11-23T09:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    StockLedger ledger;

    @Autowired
    StockItemStore items;

    @Autowired
    BatchStore batches;

    @Autowired
    ReorderPolicyStore reorder;

    @Autowired
    ApplicationEvents events;

    private static StockLine in(BalanceKey key, String qty, String cost) {
        return new StockLine(
                key,
                MovementType.RECEIPT,
                new BigDecimal(qty),
                new BigDecimal(cost),
                true,
                "TEST",
                null,
                null,
                null,
                AT);
    }

    private static StockLine out(BalanceKey key, String qty) {
        return new StockLine(
                key, MovementType.SALE, new BigDecimal(qty).negate(), null, true, "TEST", null, null, null, AT);
    }

    private BigDecimal onHand(InventoryApi api, BalanceKey key) {
        return api.sql(j -> j.sql("""
                        SELECT on_hand FROM inventory.stock_balance
                         WHERE variant_id = :v AND location_id = :l AND stock_state = :s AND batch_id = :b
                        """)
                .param("v", key.variantId())
                .param("l", key.locationId())
                .param("s", key.stockState())
                .param("b", key.batchId())
                .query(BigDecimal.class)
                .single());
    }

    @Test
    void inventorySchemaIsCoveredByRowLevelSecurityAndTheLedgerGrantsNoUpdates() throws Exception {
        RlsCoverage.assertCovered(List.of("inventory"), List.of("inventory.stock_movement"));
        List<String> partitionGrants = jdbc.sql("""
                        SELECT table_name FROM information_schema.role_table_grants
                         WHERE grantee = 'bmp_app' AND table_schema = 'inventory' AND table_name LIKE 'stock_movement_%'
                        """).query(String.class).list();
        assertThat(partitionGrants)
                .as("partitions are reached through the ledger only")
                .isEmpty();
    }

    @Test
    void inv01Inv03MovementsUpdateBalancesInTheSameTransaction() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Ledger");
        BalanceKey eggs = BalanceKey.of(UUID.randomUUID(), api.location, "DEFAULT");

        List<PlannedMovement> posted = api.inTenant(
                () -> ledger.post(List.of(in(eggs, "300", "12750.00"), in(eggs, "300", "13500.00"), out(eggs, "6"))));

        assertThat(posted)
                .extracting(PlannedMovement::type)
                .containsExactly(MovementType.RECEIPT, MovementType.RECEIPT, MovementType.SALE);
        assertThat(posted.getLast().costAmount()).as("6 × 43.75").isEqualByComparingTo("-262.50");
        Map<String, Object> balance =
                api.sql(j -> j.sql("""
                        SELECT on_hand, stock_value, avg_unit_cost, last_movement_id FROM inventory.stock_balance
                         WHERE variant_id = :v
                        """).param("v", eggs.variantId()).query().singleRow());
        assertThat((BigDecimal) balance.get("on_hand")).isEqualByComparingTo("594");
        assertThat((BigDecimal) balance.get("stock_value")).isEqualByComparingTo("25987.50");
        assertThat((BigDecimal) balance.get("avg_unit_cost")).isEqualByComparingTo("43.750000");
        assertThat(balance.get("last_movement_id")).isEqualTo(posted.getLast().id());
        BigDecimal ledgerValue =
                api.sql(j -> j.sql("SELECT sum(cost_amount) FROM inventory.stock_movement WHERE variant_id = :v")
                        .param("v", eggs.variantId())
                        .query(BigDecimal.class)
                        .single());
        assertThat(ledgerValue).as("value = Σ movement costs").isEqualByComparingTo("25987.50");
    }

    @Test
    void inv07NegativeStockIsFlaggedInTheLedger() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Negative");
        BalanceKey eggs = BalanceKey.of(UUID.randomUUID(), api.location, "DEFAULT");

        PlannedMovement sale =
                api.inTenant(() -> ledger.post(List.of(out(eggs, "3")))).getFirst();

        assertThat(sale.flags()).containsExactly(StockFlag.NEGATIVE_STOCK);
        assertThat(api.<String>sql(
                        j -> j.sql("SELECT array_to_string(flags, ',') FROM inventory.stock_movement WHERE id = :id")
                                .param("id", sale.id())
                                .query(String.class)
                                .single()))
                .isEqualTo("NEGATIVE_STOCK");
        assertThat(onHand(api, eggs)).isEqualByComparingTo("-3");
    }

    @Test
    void inv04FifoCostsIssuesFromTheOldestLayers() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Fifo");
        ApiClient client = api.client;
        var settings = client.get("/api/v1/settings/inventory", api.owner);
        assertThat(client.patch(
                        "/api/v1/settings/inventory",
                        api.owner,
                        "{\"values\": {\"costingMethod\": \"FIFO\"}}",
                        ApiClient.etag(settings)))
                .hasStatusOk();
        BalanceKey eggs = BalanceKey.of(UUID.randomUUID(), api.location, "DEFAULT");

        List<PlannedMovement> posted =
                api.inTenant(() -> ledger.post(List.of(in(eggs, "300", "12750.00"), in(eggs, "300", "13500.00"))));
        PlannedMovement issue =
                api.inTenant(() -> ledger.post(List.of(out(eggs, "400")))).getFirst();

        assertThat(posted).hasSize(2);
        assertThat(issue.costAmount()).as("300 × 42.50 + 100 × 45.00").isEqualByComparingTo("-17250.00");
        assertThat(api.<BigDecimal>sql(j -> j.sql("""
                        SELECT sum(qty_remaining) FROM inventory.cost_layer WHERE variant_id = :v
                        """)
                        .param("v", eggs.variantId())
                        .query(BigDecimal.class)
                        .single()))
                .isEqualByComparingTo("200");
    }

    @Test
    void inv14IssuesOfBatchTrackedVariantsTakeTheEarliestExpiryFirst() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Batches");
        UUID variant = UUID.randomUUID();
        api.inTenant(() -> {
            items.upsert(new StockItem(
                    variant,
                    UUID.randomUUID(),
                    "MILK",
                    "Milk",
                    "piece",
                    List.of("DEFAULT"),
                    "PREPACKED",
                    List.of(new StockUnit(
                            UUID.randomUUID(), "piece", BigDecimal.ONE, "DEFAULT", true, true, true, true)),
                    true,
                    true,
                    false,
                    true,
                    1));
            return true;
        });
        BalanceKey late = api.inTenant(() -> BalanceKey.of(variant, api.location, "DEFAULT")
                .withBatch(batches.findOrCreate(variant, "LATE", LocalDate.parse("2026-12-31"), AT, null)
                        .id()));
        BalanceKey early = api.inTenant(() -> BalanceKey.of(variant, api.location, "DEFAULT")
                .withBatch(batches.findOrCreate(variant, "EARLY", LocalDate.parse("2026-11-30"), AT, null)
                        .id()));
        api.inTenant(() -> ledger.post(List.of(in(late, "10", "100.00"), in(early, "10", "120.00"))));

        List<PlannedMovement> issued =
                api.inTenant(() -> ledger.post(List.of(out(BalanceKey.of(variant, api.location, "DEFAULT"), "14"))));

        assertThat(issued).extracting(m -> m.key().batchId()).containsExactly(early.batchId(), late.batchId());
        assertThat(issued)
                .extracting(m -> m.qtyBase().stripTrailingZeros().toPlainString())
                .containsExactly("-10", "-4");
        assertThat(onHand(api, early)).isEqualByComparingTo("0");
        assertThat(onHand(api, late)).isEqualByComparingTo("6");
        List<PlannedMovement> beyond =
                api.inTenant(() -> ledger.post(List.of(out(BalanceKey.of(variant, api.location, "DEFAULT"), "8"))));
        assertThat(beyond)
                .as("what no batch covers takes the last batch below zero")
                .singleElement()
                .satisfies(m -> assertThat(m.key().batchId()).isEqualTo(late.batchId()));
        assertThat(onHand(api, late)).isEqualByComparingTo("-2");
    }

    @Test
    void inv14BatchesWithoutExpiryComeLastAndThePostingRecordsItsActor() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Batches without expiry");
        UUID variant = UUID.randomUUID();
        BalanceKey plain = BalanceKey.of(variant, api.location, "DEFAULT");
        api.inTenant(() -> {
            items.upsert(new StockItem(
                    variant,
                    UUID.randomUUID(),
                    "RICE",
                    "Rice",
                    "g",
                    List.of("DEFAULT"),
                    "PREPACKED",
                    List.of(),
                    true,
                    false,
                    true,
                    true,
                    1));
            return true;
        });
        BalanceKey undated = api.inTenant(() -> plain.withBatch(
                batches.findOrCreate(variant, "NO-DATE", null, AT, null).id()));
        BalanceKey dated = api.inTenant(
                () -> plain.withBatch(batches.findOrCreate(variant, "DATED", LocalDate.parse("2027-01-31"), AT, null)
                        .id()));
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();

        List<PlannedMovement> received = ActorContext.callWith(
                new Actor(UserId.of(user), DeviceId.of(device)),
                () -> api.inTenant(() -> ledger.post(
                        List.of(in(undated, "5", "50.00"), in(dated, "5", "60.00"), in(plain, "1", "10.00")))));
        List<PlannedMovement> issued = api.inTenant(() -> ledger.post(List.of(out(plain, "7"), out(undated, "1"))));

        assertThat(received).hasSize(3);
        assertThat(issued)
                .extracting(m -> m.key().batchId())
                .as("dated batches first, then undated; a named batch is taken as named")
                .containsExactly(dated.batchId(), undated.batchId(), undated.batchId());
        assertThat(onHand(api, undated)).isEqualByComparingTo("2");
        assertThat(onHand(api, plain))
                .as("incoming stock without a batch stays without one")
                .isEqualByComparingTo("1");
        Map<String, Object> actor =
                api.sql(j -> j.sql("SELECT actor_id, device_id FROM inventory.stock_movement WHERE id = :id")
                        .param("id", received.getFirst().id())
                        .query()
                        .singleRow());
        assertThat(actor.get("actor_id")).isEqualTo(user);
        assertThat(actor.get("device_id")).isEqualTo(device);
        assertThat(api.inTenant(() -> ledger.post(List.of()))).isEmpty();
    }

    @Test
    void inv13CrossingTheReorderLevelRaisesOneAlertAndRisingAboveClearsIt() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Reorder");
        BalanceKey eggs = BalanceKey.of(UUID.randomUUID(), api.location, "DEFAULT");
        api.inTenant(() -> {
            reorder.save(new ReorderPolicy(
                    eggs.variantId(), api.location, new BigDecimal("120"), new BigDecimal("600"), null, false, 0));
            return ledger.post(List.of(in(eggs, "150", "6375.00")));
        });

        api.inTenant(() -> ledger.post(List.of(out(eggs, "20"))));
        api.inTenant(() -> ledger.post(List.of(out(eggs, "20"))));
        api.inTenant(() -> ledger.post(List.of(out(eggs, "5"))));

        assertThat(events.stream(StockLevelLow.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.available()).isEqualByComparingTo("110");
                    assertThat(e.reorderLevel()).isEqualByComparingTo("120");
                });
        api.inTenant(() -> ledger.post(List.of(in(eggs, "300", "12750.00"))));
        assertThat(api.<Boolean>sql(j -> j.sql("SELECT low_flag FROM inventory.reorder_policy WHERE variant_id = :v")
                        .param("v", eggs.variantId())
                        .query(Boolean.class)
                        .single()))
                .isFalse();
        api.inTenant(() -> ledger.post(List.of(out(eggs, "400"))));
        assertThat(events.stream(StockLevelLow.class).filter(e -> e.tenantId().equals(api.tenant)))
                .as("a second crossing alerts again")
                .hasSize(2);
    }

    @Test
    void fiftyConcurrentIssuesOnOneBalanceLeaveTheExactQuantity() throws Exception {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Concurrency");
        BalanceKey eggs = BalanceKey.of(UUID.randomUUID(), api.location, "DEFAULT");
        api.inTenant(() -> ledger.post(List.of(in(eggs, "100", "4250.00"))));

        List<Future<?>> issues = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                issues.add(threads.submit(() -> TenantContext.runWith(
                        api.tenant,
                        () -> new TransactionTemplate(transactions)
                                .executeWithoutResult(s -> ledger.post(List.of(out(eggs, "1")))))));
            }
            for (Future<?> issue : issues) {
                issue.get();
            }
        }

        assertThat(onHand(api, eggs)).isEqualByComparingTo("50");
        assertThat(api.<Long>sql(j -> j.sql("SELECT count(*) FROM inventory.stock_movement WHERE variant_id = :v")
                        .param("v", eggs.variantId())
                        .query(Long.class)
                        .single()))
                .isEqualTo(51L);
        assertThat(api.<BigDecimal>sql(
                        j -> j.sql("SELECT stock_value FROM inventory.stock_balance WHERE variant_id = :v")
                                .param("v", eggs.variantId())
                                .query(BigDecimal.class)
                                .single()))
                .isEqualByComparingTo("2125.00");
    }
}
