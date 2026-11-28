package com.bmp.finance;

import static com.bmp.finance.FinanceShop.DAY;
import static com.bmp.finance.FinanceShop.line;
import static com.bmp.finance.FinanceShop.tender;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;

/** Row-level security on every finance table and insert-only ledgers (06 §2, §10). */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinanceIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Test
    void everyFinanceTableIsTenantIsolatedAndTheLedgersAreInsertOnly() throws Exception {
        RlsCoverage.assertCovered(
                List.of("finance"),
                List.of(
                        "finance.journal_entry",
                        "finance.journal_line",
                        "finance.open_item_settlement",
                        "finance.receipt_line",
                        "finance.expense",
                        "finance.supplier_payment",
                        "finance.supplier_payment_allocation",
                        "finance.cash_drop"));
    }

    @Test
    void anotherTenantSeesNoneOfTheJournalsAccountsAndItemsOfOne() {
        FinanceShop a = new FinanceShop(mvc, jdbc, transactions, publisher, "Finance isolation A");
        FinanceShop b = new FinanceShop(mvc, jdbc, transactions, publisher, "Finance isolation B");
        UUID customer = UUID.randomUUID();
        a.customerKnown(customer, 0);
        a.publish(a.sale(
                UUID.randomUUID(),
                customer,
                DAY,
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "100.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "100.00"))));
        a.awaitJournals("SALE", 1);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(a.count("SELECT count(*) FROM finance.open_item"))
                        .isEqualTo(1L));

        assertThat(b.journals("SALE")).isZero();
        assertThat(b.count("SELECT count(*) FROM finance.journal_line")).isZero();
        assertThat(b.count("SELECT count(*) FROM finance.open_item")).isZero();
        assertThat(b.count("SELECT count(*) FROM finance.party_ref")).isZero();
        assertThat(b.count("SELECT count(*) FROM finance.account")).isEqualTo(29L);
        assertThat(b.count("SELECT count(*) FROM finance.account a WHERE a.tenant_id <> platform.current_tenant_id()"))
                .isZero();
    }
}
