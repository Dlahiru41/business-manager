package com.bmp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.api.events.StockIssued;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.party.api.events.CreditPolicySnapshot;
import com.bmp.party.api.events.PartyCreated;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.GoodsReceivedLine;
import com.bmp.procurement.api.events.SupplierInvoicePosted;
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
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A tenant with its seeded chart, and the upstream events of a shop day, published as their modules would. */
final class FinanceShop {

    static final Instant SOLD = Instant.parse("2026-11-25T04:30:00Z");
    static final LocalDate DAY = LocalDate.parse("2026-11-25");

    final ApiClient api;
    final TenantId tenant;
    final String owner;
    final UUID location = UUID.randomUUID();
    final UUID register = UUID.randomUUID();
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;
    private final ApplicationEventPublisher publisher;

    FinanceShop(
            MockMvcTester mvc,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ApplicationEventPublisher publisher,
            String name) {
        this.api = new ApiClient(mvc);
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.publisher = publisher;
        this.tenant = api.createTenant(name + " " + UUID.randomUUID());
        this.owner = JwtTestTokens.forRole(tenant, SystemRole.OWNER);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(count("SELECT count(*) FROM finance.account"))
                        .as("the chart of accounts was seeded")
                        .isEqualTo(29L));
    }

    void publish(Object event) {
        TenantContext.runWith(
                tenant,
                () -> new TransactionTemplate(transactions).executeWithoutResult(s -> publisher.publishEvent(event)));
    }

    <T> T sql(Function<JdbcClient, T> query) {
        return TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactions).execute(s -> query.apply(jdbc)));
    }

    long count(String sql) {
        return this.<Long>sql(j -> j.sql(sql).query(Long.class).single());
    }

    /** Σ debit − credit of a seeded account. */
    BigDecimal balance(String systemKey) {
        return sql(
                j -> j.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM finance.journal_line l
                          JOIN finance.account a ON a.tenant_id = l.tenant_id AND a.id = l.account_id
                         WHERE a.system_key = :key
                        """).param("key", systemKey).query(BigDecimal.class).single());
    }

    long journals(String sourceType) {
        return this.<Long>sql(j -> j.sql("SELECT count(*) FROM finance.journal_entry WHERE source_type = :type")
                .param("type", sourceType)
                .query(Long.class)
                .single());
    }

    UUID accountId(String systemKey) {
        return sql(j -> j.sql("SELECT id FROM finance.account WHERE system_key = :k")
                .param("k", systemKey)
                .query(UUID.class)
                .single());
    }

    UUID categoryId(String code) {
        return sql(j -> j.sql("SELECT id FROM finance.expense_category WHERE code = :c")
                .param("c", code)
                .query(UUID.class)
                .single());
    }

    /** Publishes a supplier and waits until finance knows its terms. */
    void supplierKnown(UUID party, int terms) {
        publish(new PartyCreated(
                UUID.randomUUID(),
                tenant,
                SOLD,
                party,
                1,
                EventMetadata.empty(),
                PartyId.of(party),
                "SUPPLIER",
                "Egg farm",
                null,
                "ACTIVE",
                false,
                "LKR",
                new CreditPolicySnapshot(false, BigDecimal.ZERO, terms)));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                                this.<Long>sql(j -> j.sql("SELECT count(*) FROM finance.party_ref WHERE party_id = :p")
                                        .param("p", party)
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(1L));
    }

    /** Publishes the customer and waits until finance knows its terms (a credit bill before it would wait a minute). */
    void customerKnown(UUID party, int terms) {
        publish(customer(party, terms));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                                this.<Long>sql(j -> j.sql("SELECT count(*) FROM finance.party_ref WHERE party_id = :p")
                                        .param("p", party)
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(1L));
    }

    void awaitJournals(String sourceType, long expected) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(journals(sourceType)).as(sourceType).isEqualTo(expected));
    }

    /** Journals whose debits and credits differ: none, ever. */
    long unbalanced() {
        return count("""
                SELECT count(*) FROM (SELECT journal_id FROM finance.journal_line
                                       GROUP BY journal_id HAVING sum(debit) <> sum(credit)) u
                """);
    }

    // ------------------------------------------------------------------------------------------ upstream events

    static SaleLine line(UUID lineId, UUID variant, String total, String tax, String discount) {
        return new SaleLine(
                lineId,
                VariantId.of(variant),
                PackagingUnitId.of(UUID.randomUUID()),
                "Item",
                BigDecimal.ONE,
                BigDecimal.ONE,
                "DEFAULT",
                false,
                new BigDecimal(total).add(new BigDecimal(discount)),
                new BigDecimal(discount),
                BigDecimal.ZERO,
                new BigDecimal(tax),
                new BigDecimal(total));
    }

    static SaleTender tender(String method, String amount) {
        return new SaleTender(UUID.randomUUID(), method, new BigDecimal(amount), null, null, null);
    }

    SaleCompleted sale(
            UUID bill, @Nullable UUID customer, LocalDate day, List<SaleLine> lines, List<SaleTender> tenders) {
        BigDecimal total = lines.stream().map(SaleLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = lines.stream().map(SaleLine::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SaleCompleted(
                UUID.randomUUID(),
                tenant,
                SOLD,
                bill,
                1,
                EventMetadata.empty(),
                bill,
                "R1-" + bill.toString().substring(30),
                "RETAIL",
                customer,
                LocationId.of(location),
                register,
                null,
                null,
                null,
                null,
                null,
                day,
                SOLD,
                "LKR",
                false,
                List.of(),
                lines,
                new SaleTotals(total, BigDecimal.ZERO, tax, total),
                tenders);
    }

    PaymentCaptured payment(UUID payment, @Nullable UUID bill, @Nullable UUID customer, String method, String amount) {
        return new PaymentCaptured(
                UUID.randomUUID(),
                tenant,
                SOLD,
                payment,
                0,
                EventMetadata.empty(),
                payment,
                bill == null ? "ACCOUNT" : "BILL",
                bill,
                null,
                customer,
                method,
                new BigDecimal(amount),
                null,
                null,
                null,
                register,
                null,
                "LKR",
                SOLD,
                List.of());
    }

    PaymentRefunded refund(UUID payment, UUID bill, @Nullable UUID creditNote, String method, String amount) {
        return new PaymentRefunded(
                UUID.randomUUID(),
                tenant,
                SOLD,
                payment,
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                payment,
                bill,
                creditNote,
                null,
                new BigDecimal(amount),
                method,
                "Customer changed mind",
                null,
                "LKR",
                SOLD);
    }

    StockIssued issued(String type, UUID source, UUID bill, List<StockMovementLine> lines) {
        return new StockIssued(
                UUID.randomUUID(),
                tenant,
                SOLD,
                source,
                0,
                EventMetadata.empty(),
                type,
                source,
                bill,
                LocationId.of(location),
                "LKR",
                lines,
                List.of());
    }

    static StockMovementLine cost(UUID sourceLine, UUID variant, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new StockMovementLine(
                UUID.randomUUID(),
                sourceLine,
                VariantId.of(variant),
                "DEFAULT",
                null,
                BigDecimal.valueOf(value.signum()),
                value.abs(),
                value);
    }

    SaleReturned returned(
            UUID creditNote,
            UUID bill,
            @Nullable UUID customer,
            String total,
            List<ReturnedLine> lines,
            List<RefundTender> refunds) {
        return new SaleReturned(
                UUID.randomUUID(),
                tenant,
                SOLD,
                bill,
                2,
                EventMetadata.empty(),
                creditNote,
                "CN-000001",
                bill,
                "R1-000001",
                customer,
                LocationId.of(location),
                register,
                register,
                "LKR",
                "Customer changed mind",
                new BigDecimal(total),
                lines.stream().map(ReturnedLine::tax).reduce(BigDecimal.ZERO, BigDecimal::add),
                lines,
                refunds,
                SOLD);
    }

    static ReturnedLine returnedLine(UUID line, UUID variant, String amount, String tax, boolean restock) {
        return new ReturnedLine(
                line,
                VariantId.of(variant),
                BigDecimal.ONE,
                BigDecimal.ONE,
                "DEFAULT",
                new BigDecimal(amount),
                new BigDecimal(tax),
                restock);
    }

    SaleVoided voided(SaleCompleted sale) {
        return new SaleVoided(
                UUID.randomUUID(),
                tenant,
                SOLD,
                sale.billId(),
                2,
                EventMetadata.empty(),
                sale.billId(),
                sale.number(),
                sale.customerId(),
                sale.locationId(),
                register,
                register,
                "LKR",
                "Wrong customer",
                UUID.randomUUID(),
                sale.totals().grandTotal(),
                sale.tenders(),
                SOLD);
    }

    PartyCreated customer(UUID party, int terms) {
        return new PartyCreated(
                UUID.randomUUID(),
                tenant,
                SOLD,
                party,
                1,
                EventMetadata.empty(),
                PartyId.of(party),
                "CUSTOMER",
                "Wholesale customer",
                null,
                "ACTIVE",
                false,
                "LKR",
                new CreditPolicySnapshot(true, new BigDecimal("50000"), terms));
    }

    GoodsReceived goodsReceived(UUID receipt, UUID supplier, List<GoodsReceivedLine> lines) {
        BigDecimal total = lines.stream().map(GoodsReceivedLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GoodsReceived(
                UUID.randomUUID(),
                tenant,
                SOLD,
                receipt,
                0,
                EventMetadata.empty(),
                receipt,
                "GRN-000001",
                PartyId.of(supplier),
                LocationId.of(location),
                null,
                null,
                SOLD,
                "LKR",
                total,
                lines);
    }

    static GoodsReceivedLine receiptLine(UUID line, UUID variant, String total) {
        BigDecimal value = new BigDecimal(total);
        return new GoodsReceivedLine(
                line,
                null,
                VariantId.of(variant),
                PackagingUnitId.of(UUID.randomUUID()),
                BigDecimal.ONE,
                BigDecimal.ONE,
                "DEFAULT",
                value,
                value,
                value,
                null,
                null);
    }

    SupplierInvoicePosted invoice(UUID invoice, UUID supplier, UUID receipt, String subtotal, String receiptValue) {
        BigDecimal sub = new BigDecimal(subtotal);
        BigDecimal value = new BigDecimal(receiptValue);
        return new SupplierInvoicePosted(
                UUID.randomUUID(),
                tenant,
                SOLD,
                invoice,
                0,
                EventMetadata.empty(),
                invoice,
                PartyId.of(supplier),
                "INV-778",
                DAY,
                DAY.plusDays(30),
                "LKR",
                sub,
                BigDecimal.ZERO,
                sub,
                value,
                sub.subtract(value),
                List.of(receipt));
    }
}
