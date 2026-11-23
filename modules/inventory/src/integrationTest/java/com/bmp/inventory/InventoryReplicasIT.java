package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.catalog.api.events.PackagingUnitSnapshot;
import com.bmp.catalog.api.events.ProductTracking;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.port.DefinitionStore;
import com.bmp.inventory.domain.port.StockItemStore;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

/** T02-03: inventory keeps replicas of catalog variants, products, definitions and tenant locations. */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class InventoryReplicasIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    StockItemStore items;

    @Autowired
    DefinitionStore definitions;

    private void publish(InventoryApi api, DomainEvent event) {
        TenantContext.runWith(
                api.tenant,
                () -> new TransactionTemplate(transactions).executeWithoutResult(s -> publisher.publishEvent(event)));
    }

    private static VariantCreated created(InventoryApi api, UUID variant, long version, String sku, UUID eventId) {
        return new VariantCreated(
                eventId,
                api.tenant,
                Instant.parse("2026-11-23T09:00:00Z"),
                variant,
                version,
                EventMetadata.empty(),
                VariantId.of(variant),
                ProductId.of(UUID.randomUUID()),
                sku,
                sku,
                List.of(),
                List.of("DEFAULT"),
                "PREPACKED",
                "ACTIVE",
                "piece",
                null,
                new ProductTracking(true, true, false, false, false),
                List.of(new PackagingUnitSnapshot(
                        PackagingUnitId.of(UUID.randomUUID()),
                        "piece",
                        "Piece",
                        BigDecimal.ONE,
                        "DEFAULT",
                        true,
                        true,
                        true,
                        true,
                        "ACTIVE",
                        List.of())));
    }

    private Optional<StockItem> item(InventoryApi api, UUID variant) {
        return TenantContext.callWith(
                api.tenant, () -> new TransactionTemplate(transactions).execute(s -> items.find(variant)));
    }

    @Test
    void variantsUnitsAndPublishedDefinitionsReachInventory() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Replicas");

        JsonNode oil = variant(api.product(OIL), "OIL");

        StockItem item = item(api, id(oil)).orElseThrow();
        assertThat(item.sku()).isEqualTo("OIL");
        assertThat(item.baseUom()).isEqualTo("ml");
        assertThat(item.stockStates()).containsExactly("SEALED", "OPEN");
        assertThat(item.sellMode()).isEqualTo("BREAK_ON_SALE");
        assertThat(item.units()).hasSize(3);
        assertThat(item.unit(UUID.fromString(InventoryApi.unit(oil, "can")))
                        .orElseThrow()
                        .qtyInBase())
                .isEqualByComparingTo("19565.28");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM inventory.transformation_def_ref WHERE kind = 'OPEN'")
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(1L));
        UUID definition = api.sql(j -> j.sql("SELECT definition_id FROM inventory.transformation_def_ref")
                .query(UUID.class)
                .single());
        DefinitionRef ref = api.inTenant(() -> definitions.latest(definition).orElseThrow());
        assertThat(ref.active()).isTrue();
        assertThat(ref.inputs())
                .singleElement()
                .satisfies(l -> assertThat(l.stockState()).isEqualTo("SEALED"));
        assertThat(ref.outputs())
                .singleElement()
                .satisfies(l -> assertThat(l.qtyBase()).isEqualByComparingTo("19565.28"));
        assertThat(api.<Long>sql(j -> j.sql("SELECT count(*) FROM inventory.stock_product")
                        .query(Long.class)
                        .single()))
                .isEqualTo(1L);
        api.inTenant(() -> {
            definitions.retire(definition, ref.defVersion());
            return true;
        });
        assertThat(api.inTenant(() -> definitions.latest(definition).orElseThrow())
                        .active())
                .isFalse();
    }

    @Test
    void redeliveredAndOutOfOrderEventsNeverMoveTheReplicaBackwards() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Replica order");
        UUID variant = UUID.randomUUID();
        UUID first = UUID.randomUUID();

        publish(api, created(api, variant, 2, "NEW", first));
        await().atMost(Duration.ofSeconds(20)).until(() -> item(api, variant).isPresent());
        publish(api, created(api, variant, 1, "OLD", UUID.randomUUID()));
        publish(api, created(api, variant, 3, "REDELIVERED", first));

        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            StockItem item = item(api, variant).orElseThrow();
            assertThat(item.sku())
                    .as("older versions and redeliveries are ignored")
                    .isEqualTo("NEW");
            assertThat(item.trackBatches()).isTrue();
            assertThat(item.trackExpiry()).isTrue();
        });

        UUID ghost = UUID.randomUUID();
        publish(
                api,
                new VariantDeactivated(
                        UUID.randomUUID(),
                        api.tenant,
                        Instant.now(),
                        ghost,
                        5,
                        EventMetadata.empty(),
                        VariantId.of(ghost),
                        ProductId.of(UUID.randomUUID()),
                        "GHOST"));
        await().atMost(Duration.ofSeconds(20)).until(() -> item(api, ghost).isPresent());
        publish(api, created(api, ghost, 4, "GHOST", UUID.randomUUID()));
        await().pollDelay(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(item(api, ghost).orElseThrow().active())
                        .as("a deactivation that overtook the creation wins")
                        .isFalse());
    }
}
