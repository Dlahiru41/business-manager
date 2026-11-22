package com.bmp.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.persistence.event.InboxGuard;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.test.persistence.BmpPostgres;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = PersistenceTestApplication.class)
@WithPostgres
class PersistenceInfrastructureIT {

    @Autowired
    NumberSequences numbers;

    @Autowired
    InboxGuard inbox;

    @Autowired
    TransactionTemplate tx;

    private final TenantId tenantA = TenantId.of(UUID.randomUUID());
    private final TenantId tenantB = TenantId.of(UUID.randomUUID());

    private <T> T inTenant(TenantId tenant, Supplier<T> work) {
        return TenantContext.callWith(tenant, () -> tx.execute(status -> work.get()));
    }

    @Test
    void numberSequencesShouldCountPerTenantAndScope() {
        assertThat(inTenant(tenantA, () -> numbers.next("PO"))).isEqualTo(1);
        assertThat(inTenant(tenantA, () -> numbers.next("PO"))).isEqualTo(2);
        assertThat(inTenant(tenantA, () -> numbers.next("GRN"))).isEqualTo(1);
        assertThat(inTenant(tenantB, () -> numbers.next("PO"))).isEqualTo(1);
    }

    @Test
    void numberSequencesShouldGiveTheNumberBackOnRollback() {
        inTenant(tenantA, () -> numbers.next("CN"));
        TenantContext.runWith(
                tenantA,
                () -> tx.executeWithoutResult(status -> {
                    numbers.next("CN");
                    status.setRollbackOnly();
                }));

        assertThat(inTenant(tenantA, () -> numbers.next("CN"))).isEqualTo(2);
    }

    @Test
    void numberSequencesShouldNeverHandOutDuplicatesUnderConcurrency() throws Exception {
        List<Future<Long>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> inTenant(tenantA, () -> numbers.next("CONCURRENT"))));
            }
        }
        Set<Long> allocated = new HashSet<>();
        for (Future<Long> future : futures) {
            allocated.add(future.get());
        }

        assertThat(allocated).hasSize(20).allMatch(n -> n >= 1 && n <= 20);
    }

    @Test
    void numberSequencesShouldRequireATransaction() {
        assertThatThrownBy(() -> TenantContext.runWith(tenantA, () -> numbers.next("PO")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void inboxShouldAcceptEachEventOncePerConsumer() {
        UUID eventId = UUID.randomUUID();

        assertThat(inTenant(tenantA, () -> inbox.firstDelivery("test.ConsumerA", eventId)))
                .isTrue();
        assertThat(inTenant(tenantA, () -> inbox.firstDelivery("test.ConsumerA", eventId)))
                .isFalse();
        assertThat(inTenant(tenantA, () -> inbox.firstDelivery("test.ConsumerB", eventId)))
                .isTrue();
        assertThatThrownBy(() -> inbox.firstDelivery("test.ConsumerA", UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void platformAndModuleSchemasShouldBeFullyCoveredByRls() throws Exception {
        RlsCoverage.assertCovered(List.of("platform", "sample"), List.of("sample.entry"));
    }

    @Test
    void rlsCoverageShouldReportUnprotectedTablesAndWritableLedgers() throws Exception {
        try (Connection owner = BmpPostgres.superuserConnection();
                Statement sql = owner.createStatement()) {
            sql.execute("CREATE SCHEMA IF NOT EXISTS rls_probe");
            sql.execute("CREATE TABLE IF NOT EXISTS rls_probe.leaky (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)");
            sql.execute("GRANT USAGE ON SCHEMA rls_probe TO bmp_app");
            sql.execute("GRANT SELECT, UPDATE ON rls_probe.leaky TO bmp_app");
            sql.execute(
                    "CREATE TABLE IF NOT EXISTS rls_probe.permissive (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)");
            sql.execute("ALTER TABLE rls_probe.permissive ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE rls_probe.permissive FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY anything ON rls_probe.permissive USING (true) WITH CHECK (true)");
            sql.execute("CREATE TABLE IF NOT EXISTS rls_probe.untenanted (id uuid PRIMARY KEY)");
            try {
                assertThat(RlsCoverage.violations(List.of("rls_probe"), List.of("rls_probe.leaky")))
                        .contains(
                                "rls_probe.leaky has no row-level security",
                                "rls_probe.leaky does not force row-level security",
                                "rls_probe.leaky has no policy comparing with platform.current_tenant_id() in both USING and WITH CHECK",
                                "ledger grants bmp_app rls_probe.leaky:UPDATE",
                                "rls_probe.permissive has no policy comparing with platform.current_tenant_id() in both"
                                        + " USING and WITH CHECK",
                                "rls_probe.untenanted has neither a tenant_id column nor row-level security");
            } finally {
                sql.execute("DROP SCHEMA rls_probe CASCADE");
            }
        }
    }
}
