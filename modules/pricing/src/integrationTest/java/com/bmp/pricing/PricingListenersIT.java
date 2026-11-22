package com.bmp.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.catalog.api.events.PackagingUnitSnapshot;
import com.bmp.catalog.api.events.ProductTracking;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.pricing.domain.port.VariantRefStore;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.VariantId;
import com.bmp.tenant.api.events.TenantCreated;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

/**
 * The pricing listeners: catalog variants reach the replica once (inbox), in the event's tenant, and never move
 * backwards when events arrive out of order; a redelivered TenantCreated seeds nothing twice (07 par. 7).
 */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PricingListenersIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    VariantRefStore replica;

    private void publish(DomainEvent event) {
        TenantContext.runWith(
                event.tenantId(),
                () -> new TransactionTemplate(transactions).executeWithoutResult(s -> publisher.publishEvent(event)));
    }

    private static PackagingUnitSnapshot unit(UUID id, String code, String qty, boolean base) {
        return new PackagingUnitSnapshot(
                PackagingUnitId.of(id),
                code,
                code,
                new BigDecimal(qty),
                "DEFAULT",
                base,
                true,
                true,
                base,
                "ACTIVE",
                List.of());
    }

    private static VariantCreated created(PricingApi api, UUID variant, UUID product, long version, UUID unit) {
        return new VariantCreated(
                UUID.randomUUID(),
                api.tenant,
                Instant.now(),
                variant,
                version,
                EventMetadata.empty(),
                VariantId.of(variant),
                ProductId.of(product),
                "X",
                "X",
                List.of(),
                List.of("DEFAULT"),
                "PREPACKED",
                "ACTIVE",
                "piece",
                null,
                new ProductTracking(false, false, false, false, false),
                List.of(unit(unit, "piece", "1", true)));
    }

    private static VariantUpdated updated(
            PricingApi api, UUID variant, UUID product, long version, String status, int units) {
        List<PackagingUnitSnapshot> snapshot = units == 1
                ? List.of(unit(UUID.randomUUID(), "piece", "1", true))
                : List.of(unit(UUID.randomUUID(), "piece", "1", true), unit(UUID.randomUUID(), "tray", "30", false));
        return new VariantUpdated(
                UUID.randomUUID(),
                api.tenant,
                Instant.now(),
                variant,
                version,
                EventMetadata.empty(),
                VariantId.of(variant),
                ProductId.of(product),
                "X",
                "X",
                List.of(),
                List.of("DEFAULT"),
                "PREPACKED",
                status,
                "piece",
                null,
                new ProductTracking(false, false, false, false, false),
                snapshot);
    }

    private VariantRef replicated(PricingApi api, UUID variant, java.util.function.Predicate<VariantRef> condition) {
        return org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .until(
                        () -> api.sql(j -> replica.findAll(List.of(variant))).get(variant),
                        ref -> ref != null && condition.test(ref));
    }

    @Test
    void aRedeliveredVariantEventChangesTheReplicaOnce() {
        PricingApi api = new PricingApi(mvc, jdbc, transactions, "Listeners once");
        UUID variant = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        VariantCreated event = created(api, variant, product, 0, UUID.randomUUID());

        publish(event);
        publish(event);

        VariantRef ref = replicated(api, variant, r -> true);
        assertThat(ref.active()).isTrue();
        assertThat(ref.units())
                .singleElement()
                .satisfies(u -> assertThat(u.isBase()).isTrue());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                                api.<Long>sql(j -> j.sql("SELECT count(*) FROM platform.inbox WHERE event_id = :e")
                                        .param("e", event.eventId())
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(1L));
    }

    @Test
    void outOfOrderEventsNeverMoveTheReplicaBackwards() {
        PricingApi api = new PricingApi(mvc, jdbc, transactions, "Listeners order");
        UUID variant = UUID.randomUUID();
        UUID product = UUID.randomUUID();

        publish(updated(api, variant, product, 3, "ACTIVE", 2));
        replicated(api, variant, r -> r.aggregateVersion() == 3);
        publish(created(api, variant, product, 0, UUID.randomUUID()));
        publish(updated(api, variant, product, 2, "ACTIVE", 1));
        UUID tombstoned = UUID.randomUUID();
        publish(new VariantDeactivated(
                UUID.randomUUID(),
                api.tenant,
                Instant.now(),
                tombstoned,
                5,
                EventMetadata.empty(),
                VariantId.of(tombstoned),
                ProductId.of(product),
                "Y"));
        replicated(api, tombstoned, r -> !r.active());
        publish(created(api, tombstoned, product, 0, UUID.randomUUID()));
        publish(updated(api, variant, product, 4, "INACTIVE", 2));

        VariantRef latest = replicated(api, variant, r -> r.aggregateVersion() == 4);
        assertThat(latest.active()).isFalse();
        assertThat(latest.units()).hasSize(2);
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            VariantRef ghost =
                    api.sql(j -> replica.findAll(List.of(tombstoned))).get(tombstoned);
            assertThat(ghost).isNotNull();
            assertThat(ghost.active())
                    .as("a deactivation that overtook the creation wins")
                    .isFalse();
            assertThat(ghost.aggregateVersion()).isEqualTo(5);
        });
    }

    @Test
    void aRedeliveredTenantCreatedSeedsNoSecondSetOfLists() {
        PricingApi api = new PricingApi(mvc, jdbc, transactions, "Listeners seed");

        publish(new TenantCreated(
                UUID.randomUUID(),
                api.tenant,
                Instant.now(),
                api.tenant.value(),
                0,
                EventMetadata.empty(),
                "RETAIL_GROCERY",
                "LKR",
                "Asia/Colombo",
                0,
                null));

        await().pollDelay(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(api.<Long>sql(j -> j.sql("SELECT count(*) FROM pricing.price_list")
                                .query(Long.class)
                                .single()))
                        .isEqualTo(2L));
        assertThat(Map.of(
                        "lists",
                        api.sql(j -> j.sql("SELECT string_agg(code, ',' ORDER BY code) FROM pricing.price_list")
                                .query(String.class)
                                .single())))
                .containsEntry("lists", "RETAIL,WHOLESALE");
    }
}
