package com.bmp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.inventory.api.events.StockIssued;
import com.bmp.inventory.api.events.StockMovementLine;
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
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A tenant and the events of a shop day, published as their modules would. */
final class ReportingShop {

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

    ReportingShop(
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

    BigDecimal sum(String sql) {
        return sql(j -> j.sql(sql).query(BigDecimal.class).single());
    }

    /** Every row of the read models, as text, to compare a rebuild with what the listeners built. */
    List<Map<String, Object>> snapshot() {
        return sql(j -> j.sql("""
                        SELECT 'line' AS t, entry_id::text AS k,
                               concat_ws('|', kind, business_date, location_id, revenue, cost, qty, has_sale, cost_known) AS v
                          FROM reporting.fact_sale_line
                        UNION ALL SELECT 'bill', bill_id::text, concat_ws('|', status, grand_total, voided_on)
                          FROM reporting.fact_bill
                        UNION ALL SELECT 'day', concat_ws('|', business_date, location_id),
                               concat_ws('|', revenue, cost, expenses, bills) FROM reporting.daily_summary
                        UNION ALL SELECT 'pay', entry_id::text, concat_ws('|', kind, amount) FROM reporting.fact_payment
                        UNION ALL SELECT 'watch', credit_note_id::text, concat_ws('|', expected, refunded)
                          FROM reporting.return_watch
                        ORDER BY 1, 2
                        """).query().listOfRows());
    }

    void assertSettled(long expectedLog) {
        assertThat(count("SELECT count(*) FROM reporting.event_log")).isEqualTo(expectedLog);
    }

    // ------------------------------------------------------------------------------------------ events

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

    SaleCompleted sale(UUID bill, @Nullable UUID customer, LocalDate day, List<SaleLine> lines, String method) {
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
                "R1-000001",
                "RETAIL",
                customer,
                LocationId.of(location),
                register,
                null,
                null,
                UUID.randomUUID(),
                null,
                null,
                day,
                SOLD,
                "LKR",
                false,
                List.of(),
                lines,
                new SaleTotals(total, BigDecimal.ZERO, tax, total),
                List.of(new SaleTender(UUID.randomUUID(), method, total, null, null, null)));
    }

    StockIssued issued(String type, UUID source, UUID bill, UUID line, UUID variant, String cost) {
        BigDecimal value = new BigDecimal(cost);
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
                List.of(new StockMovementLine(
                        UUID.randomUUID(),
                        line,
                        VariantId.of(variant),
                        "DEFAULT",
                        null,
                        BigDecimal.ONE,
                        value.abs(),
                        value)),
                List.of());
    }

    SaleReturned returned(UUID creditNote, UUID bill, UUID line, UUID variant, String amount, String method) {
        BigDecimal value = new BigDecimal(amount);
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
                null,
                LocationId.of(location),
                register,
                register,
                "LKR",
                "Changed mind",
                value,
                BigDecimal.ZERO,
                List.of(new ReturnedLine(
                        line,
                        VariantId.of(variant),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        "DEFAULT",
                        value,
                        BigDecimal.ZERO,
                        true)),
                List.of(new RefundTender(UUID.randomUUID(), method, value, null, null)),
                SOLD);
    }

    SaleVoided voided(SaleCompleted sale, Instant at) {
        return new SaleVoided(
                UUID.randomUUID(),
                tenant,
                at,
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
                "Wrong",
                null,
                sale.totals().grandTotal(),
                sale.tenders(),
                at);
    }
}
