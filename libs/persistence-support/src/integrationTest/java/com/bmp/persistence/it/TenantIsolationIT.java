package com.bmp.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.persistence.tenant.TenantSql;
import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.test.persistence.BmpPostgres;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.time.MutableClock;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T00-04 acceptance: connected as {@code bmp_app}, a query without tenant context fails with
 * {@code TENANT_CONTEXT_MISSING}, and tenant A can never see tenant B's rows, not even through native SQL.
 */
@SpringBootTest(classes = PersistenceTestApplication.class)
@WithPostgres
class TenantIsolationIT {

    @Autowired
    WidgetRepository widgets;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MutableClock clock;

    private final TenantId tenantA = TenantId.of(UUID.randomUUID());
    private final TenantId tenantB = TenantId.of(UUID.randomUUID());

    private <T> T inTenant(TenantId tenant, Supplier<T> work) {
        return TenantContext.callWith(tenant, () -> tx.execute(status -> work.get()));
    }

    private Widget saveWidget(TenantId tenant, String name) {
        return inTenant(tenant, () -> widgets.saveAndFlush(new Widget(UUID.randomUUID(), name)));
    }

    private static boolean isTenantContextMissing(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && TenantSql.TENANT_CONTEXT_MISSING_SQLSTATE.equals(sql.getSQLState())) {
                return sql.getMessage().contains("TENANT_CONTEXT_MISSING");
            }
        }
        return false;
    }

    @Test
    void shouldConnectAsNonOwnerAppRole() {
        String user = inTenant(
                tenantA,
                () -> jdbc.sql("SELECT current_user").query(String.class).single());

        assertThat(user).isEqualTo(BmpPostgres.APP_USER);
    }

    @Test
    void shouldFailClosedWithoutTenantContext() {
        saveWidget(tenantA, "seeded");

        assertThatThrownBy(() -> tx.execute(s -> widgets.findAll()))
                .satisfies(e ->
                        assertThat(isTenantContextMissing(e)).as("JPA read").isTrue());
        assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM sample.widget")
                        .query(Long.class)
                        .single()))
                .satisfies(e -> assertThat(isTenantContextMissing(e))
                        .as("native read in tx")
                        .isTrue());
        assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM sample.widget")
                        .query(Long.class)
                        .single())
                .satisfies(e -> assertThat(isTenantContextMissing(e))
                        .as("native read, no tx")
                        .isTrue());
        assertThatThrownBy(() -> tx.execute(s -> widgets.saveAndFlush(new Widget(UUID.randomUUID(), "x"))))
                .satisfies(e ->
                        assertThat(isTenantContextMissing(e)).as("JPA insert").isTrue());
    }

    @Test
    void shouldHideOtherTenantsRowsFromJpaAndNativeSql() {
        Widget ofA = saveWidget(tenantA, "eggs");

        inTenant(tenantB, () -> {
            assertThat(widgets.findAll()).isEmpty();
            assertThat(widgets.findById(ofA.id())).isEmpty();
            assertThat(jdbc.sql("SELECT count(*) FROM sample.widget")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM sample.widget WHERE tenant_id = :a")
                            .param("a", tenantA.value())
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("UPDATE sample.widget SET name = 'stolen' WHERE id = :id")
                            .param("id", ofA.id())
                            .update())
                    .isZero();
            assertThat(jdbc.sql("DELETE FROM sample.widget WHERE id = :id")
                            .param("id", ofA.id())
                            .update())
                    .isZero();
            return null;
        });

        assertThat(inTenant(tenantA, () -> widgets.findById(ofA.id())))
                .get()
                .extracting(Widget::name)
                .isEqualTo("eggs");
    }

    @Test
    void shouldRejectWritingRowsForAnotherTenant() {
        assertThatThrownBy(() -> inTenant(
                        tenantB,
                        () -> jdbc.sql("INSERT INTO sample.widget (id, tenant_id, name, created_at, updated_at)"
                                        + " VALUES (:id, :tenant, 'forged', now(), now())")
                                .param("id", UUID.randomUUID())
                                .param("tenant", tenantA.value())
                                .update()))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void shouldStampAuditFieldsFromClockAndActor() {
        UserId user = UserId.of(UUID.randomUUID());
        Instant created = clock.instant();

        Widget saved = ActorContext.callWith(Actor.user(user), () -> saveWidget(tenantA, "audited"));
        clock.advance(Duration.ofMinutes(5));
        Widget renamed = inTenant(tenantA, () -> {
            Widget widget = widgets.findById(saved.id()).orElseThrow();
            widget.rename("audited-renamed");
            return widgets.saveAndFlush(widget);
        });

        assertThat(saved.tenantId()).isEqualTo(tenantA.value());
        assertThat(saved.createdAt()).isEqualTo(created);
        assertThat(saved.createdBy()).isEqualTo(user.value());
        assertThat(renamed.updatedAt()).isEqualTo(created.plus(Duration.ofMinutes(5)));
        assertThat(renamed.version()).isEqualTo(1);
    }

    @Test
    void ledgerTablesShouldBeAppendOnlyForTheAppRole() throws SQLException {
        UUID entry = UUID.randomUUID();
        inTenant(
                tenantA,
                () -> jdbc.sql("INSERT INTO sample.entry (id, tenant_id, amount, occurred_at)"
                                + " VALUES (:id, :tenant, :amount, :at)")
                        .param("id", entry)
                        .param("tenant", tenantA.value())
                        .param("amount", new BigDecimal("12.5000"))
                        .param("at", OffsetDateTime.now(ZoneOffset.UTC))
                        .update());

        assertThatThrownBy(() -> inTenant(
                        tenantA,
                        () -> jdbc.sql("UPDATE sample.entry SET amount = 0 WHERE id = :id")
                                .param("id", entry)
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");
        try (Connection owner = BmpPostgres.superuserConnection()) {
            assertThat(owner.isValid(1)).isTrue();
        }
    }
}
