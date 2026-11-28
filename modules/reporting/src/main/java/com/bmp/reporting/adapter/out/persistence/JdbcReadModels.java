package com.bmp.reporting.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.reporting.domain.model.Fact;
import com.bmp.reporting.domain.port.ReadModels;
import com.bmp.shared.context.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link ReadModels} with upserts over the reporting schema (ADR-033 §2, §3). */
@Repository
class JdbcReadModels implements ReadModels {

    private final JdbcClient jdbc;

    JdbcReadModels(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static UUID tenant() {
        return TenantContext.require().value();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(boolean exclusive) {
        jdbc.sql((exclusive ? "SELECT pg_advisory_xact_lock" : "SELECT pg_advisory_xact_lock_shared")
                        + "(hashtextextended(:key, 0))")
                .param("key", "reporting:" + tenant())
                .query()
                .listOfRows();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bill(Fact.Sale s) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_bill (tenant_id, bill_id, number, business_date, location_id, register_id,
                                                         channel, customer_id, cashier_id, hour, completed_at, grand_total,
                                                         tax_total, discount_total, status)
                        VALUES (:t, :bill, :number, :date, :location, :register, :channel, :customer, :cashier, :hour,
                                :completedAt, :grand, :tax, :discount, 'COMPLETED')
                        ON CONFLICT (tenant_id, bill_id) DO NOTHING
                        """)
                .param("t", tenant())
                .param("bill", s.billId())
                .param("number", s.number())
                .param("date", Date.valueOf(s.businessDate()))
                .param("location", s.locationId())
                .param("register", s.registerId())
                .param("channel", s.channel())
                .param("customer", s.customerId())
                .param("cashier", s.cashierId())
                .param("hour", s.hour())
                .param("completedAt", Jsonb.utc(s.completedAt()))
                .param("grand", s.grandTotal())
                .param("tax", s.taxTotal())
                .param("discount", s.discountTotal())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean billKnown(UUID billId) {
        return billLocation(billId).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> billLocation(UUID billId) {
        return jdbc.sql("SELECT location_id FROM reporting.fact_bill WHERE tenant_id = :t AND bill_id = :bill")
                .param("t", tenant())
                .param("bill", billId)
                .query((rs, row) -> rs.getObject(1, UUID.class))
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void voidBill(UUID billId, LocalDate businessDate) {
        jdbc.sql("""
                        UPDATE reporting.fact_bill SET status = 'VOIDED', voided_on = :date
                         WHERE tenant_id = :t AND bill_id = :bill
                        """)
                .param("date", Date.valueOf(businessDate))
                .param("t", tenant())
                .param("bill", billId)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SoldLine> soldLines(UUID billId) {
        return jdbc.sql("""
                        SELECT bill_line_id, variant_id, packaging_unit_id, qty, qty_base, revenue, discount, tax
                          FROM reporting.fact_sale_line
                         WHERE tenant_id = :t AND bill_id = :bill AND kind = 'SALE' AND has_sale
                         ORDER BY bill_line_id
                        """)
                .param("t", tenant())
                .param("bill", billId)
                .query((rs, row) -> new SoldLine(
                        rs.getObject("bill_line_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getObject("packaging_unit_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("qty_base"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("discount"),
                        rs.getBigDecimal("tax")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> saleHalf(SaleHalf h) {
        return jdbc.sql("""
                        INSERT INTO reporting.fact_sale_line (tenant_id, entry_id, kind, bill_id, bill_line_id, source_id,
                                                              business_date, location_id, channel, customer_id, cashier_id,
                                                              variant_id, packaging_unit_id, qty, qty_base, revenue,
                                                              discount, tax, has_sale)
                        VALUES (:t, :entry, :kind, :bill, :line, :source, :date, :location, :channel, :customer, :cashier,
                                :variant, :unit, :qty, :qtyBase, :revenue, :discount, :tax, true)
                        ON CONFLICT (tenant_id, entry_id) DO UPDATE
                           SET business_date = EXCLUDED.business_date, location_id = EXCLUDED.location_id,
                               channel = EXCLUDED.channel, customer_id = EXCLUDED.customer_id,
                               cashier_id = EXCLUDED.cashier_id, variant_id = EXCLUDED.variant_id,
                               packaging_unit_id = EXCLUDED.packaging_unit_id, qty = EXCLUDED.qty,
                               qty_base = EXCLUDED.qty_base, revenue = EXCLUDED.revenue, discount = EXCLUDED.discount,
                               tax = EXCLUDED.tax, has_sale = true
                        RETURNING cost_known, cost
                        """)
                .param("t", tenant())
                .param("entry", h.entryId())
                .param("kind", h.kind())
                .param("bill", h.billId())
                .param("line", h.billLineId())
                .param("source", h.sourceId())
                .param("date", Date.valueOf(h.businessDate()))
                .param("location", h.locationId())
                .param("channel", h.channel())
                .param("customer", h.customerId())
                .param("cashier", h.cashierId())
                .param("variant", h.variantId())
                .param("unit", h.packagingUnitId())
                .param("qty", h.qty())
                .param("qtyBase", h.qtyBase())
                .param("revenue", h.revenue())
                .param("discount", h.discount())
                .param("tax", h.tax())
                .query((rs, row) -> rs.getBoolean("cost_known") ? rs.getBigDecimal("cost") : null)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Placed> costHalf(
            UUID entryId, String kind, UUID billId, UUID billLineId, UUID sourceId, BigDecimal cost) {
        return jdbc.sql("""
                        INSERT INTO reporting.fact_sale_line (tenant_id, entry_id, kind, bill_id, bill_line_id, source_id,
                                                              cost, cost_known)
                        VALUES (:t, :entry, :kind, :bill, :line, :source, :cost, true)
                        ON CONFLICT (tenant_id, entry_id) DO UPDATE
                           SET cost = reporting.fact_sale_line.cost + EXCLUDED.cost, cost_known = true
                        RETURNING has_sale, business_date, location_id
                        """)
                .param("t", tenant())
                .param("entry", entryId)
                .param("kind", kind)
                .param("bill", billId)
                .param("line", billLineId)
                .param("source", sourceId)
                .param("cost", cost)
                .query((rs, row) -> rs.getBoolean("has_sale")
                        ? new Placed(
                                Objects.requireNonNull(rs.getDate("business_date"))
                                        .toLocalDate(),
                                rs.getObject("location_id", UUID.class))
                        : null)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void summarize(
            LocalDate businessDate,
            UUID locationId,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal expenses,
            int bills) {
        jdbc.sql("""
                        INSERT INTO reporting.daily_summary (tenant_id, business_date, location_id, revenue, cost, expenses,
                                                             bills)
                        VALUES (:t, :date, :location, :revenue, :cost, :expenses, :bills)
                        ON CONFLICT (tenant_id, business_date, location_id) DO UPDATE
                           SET revenue = reporting.daily_summary.revenue + EXCLUDED.revenue,
                               cost = reporting.daily_summary.cost + EXCLUDED.cost,
                               expenses = reporting.daily_summary.expenses + EXCLUDED.expenses,
                               bills = reporting.daily_summary.bills + EXCLUDED.bills
                        """)
                .param("t", tenant())
                .param("date", Date.valueOf(businessDate))
                .param("location", locationId)
                .param("revenue", revenue)
                .param("cost", cost)
                .param("expenses", expenses)
                .param("bills", bills)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void payment(Fact.Payment p) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_payment (tenant_id, entry_id, kind, business_date, method, amount,
                                                            register_id, bill_id, credit_note_id)
                        VALUES (:t, :entry, :kind, :date, :method, :amount, :register, :bill, :creditNote)
                        ON CONFLICT (tenant_id, entry_id) DO NOTHING
                        """)
                .param("t", tenant())
                .param("entry", p.entryId())
                .param("kind", p.kind())
                .param("date", Date.valueOf(p.businessDate()))
                .param("method", p.method())
                .param("amount", p.amount())
                .param("register", p.registerId())
                .param("bill", p.billId())
                .param("creditNote", p.creditNoteId())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void expense(Fact.Expense e) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_expense (tenant_id, expense_id, business_date, category_id, amount)
                        VALUES (:t, :id, :date, :category, :amount)
                        ON CONFLICT (tenant_id, expense_id) DO NOTHING
                        """)
                .param("t", tenant())
                .param("id", e.expenseId())
                .param("date", Date.valueOf(e.businessDate()))
                .param("category", e.categoryId())
                .param("amount", e.amount())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void drawer(Fact.Drawer d) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_drawer (tenant_id, session_id, register_id, business_date, expected,
                                                           counted, variance, reconciled_at)
                        VALUES (:t, :session, :register, :date, :expected, :counted, :variance, :at)
                        ON CONFLICT (tenant_id, session_id) DO UPDATE
                           SET expected = EXCLUDED.expected, counted = EXCLUDED.counted, variance = EXCLUDED.variance,
                               reconciled_at = EXCLUDED.reconciled_at, business_date = EXCLUDED.business_date
                        """)
                .param("t", tenant())
                .param("session", d.sessionId())
                .param("register", d.registerId())
                .param("date", Date.valueOf(d.businessDate()))
                .param("expected", d.expected())
                .param("counted", d.counted())
                .param("variance", d.variance())
                .param("at", Jsonb.utc(d.reconciledAt()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void movement(Fact.Movement m) {
        for (Fact.MovementLine l : m.lines()) {
            jdbc.sql("""
                            INSERT INTO reporting.fact_stock_movement (tenant_id, movement_id, source, business_date,
                                                                       location_id, variant_id, reason, qty_base,
                                                                       cost_amount)
                            VALUES (:t, :id, :source, :date, :location, :variant, :reason, :qty, :cost)
                            ON CONFLICT (tenant_id, movement_id) DO NOTHING
                            """)
                    .param("t", tenant())
                    .param("id", l.movementId())
                    .param("source", m.source())
                    .param("date", Date.valueOf(m.businessDate()))
                    .param("location", m.locationId())
                    .param("variant", l.variantId())
                    .param("reason", m.reason())
                    .param("qty", l.qtyBase())
                    .param("cost", l.cost())
                    .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void variant(Fact.VariantNamed v) {
        jdbc.sql("""
                        INSERT INTO reporting.variant_ref (tenant_id, variant_id, product_id, sku, name, version)
                        VALUES (:t, :variant, :product, :sku, :name, :version)
                        ON CONFLICT (tenant_id, variant_id) DO UPDATE
                           SET product_id = EXCLUDED.product_id, sku = EXCLUDED.sku, name = EXCLUDED.name,
                               version = EXCLUDED.version
                         WHERE reporting.variant_ref.version <= EXCLUDED.version
                        """)
                .param("t", tenant())
                .param("variant", v.variantId())
                .param("product", v.productId())
                .param("sku", v.sku())
                .param("name", v.name())
                .param("version", v.version())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void product(Fact.ProductNamed p) {
        jdbc.sql("""
                        INSERT INTO reporting.product_ref (tenant_id, product_id, name, category_id, version)
                        VALUES (:t, :product, :name, :category, :version)
                        ON CONFLICT (tenant_id, product_id) DO UPDATE
                           SET name = EXCLUDED.name, category_id = EXCLUDED.category_id, version = EXCLUDED.version
                         WHERE reporting.product_ref.version <= EXCLUDED.version
                        """)
                .param("t", tenant())
                .param("product", p.productId())
                .param("name", p.name())
                .param("category", p.categoryId())
                .param("version", p.version())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnWatch(Fact.Return r) {
        jdbc.sql("""
                        INSERT INTO reporting.return_watch (tenant_id, credit_note_id, bill_id, returned_at, expected)
                        VALUES (:t, :note, :bill, :at, :expected)
                        ON CONFLICT (tenant_id, credit_note_id) DO UPDATE
                           SET bill_id = EXCLUDED.bill_id, returned_at = EXCLUDED.returned_at, expected = EXCLUDED.expected
                        """)
                .param("t", tenant())
                .param("note", r.creditNoteId())
                .param("bill", r.billId())
                .param("at", Jsonb.utc(r.returnedAt()))
                .param("expected", r.moneyRefund())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundSeen(UUID creditNoteId, BigDecimal amount) {
        jdbc.sql("""
                        INSERT INTO reporting.return_watch (tenant_id, credit_note_id, refunded) VALUES (:t, :note, :amount)
                        ON CONFLICT (tenant_id, credit_note_id) DO UPDATE
                           SET refunded = reporting.return_watch.refunded + EXCLUDED.refunded
                        """)
                .param("t", tenant())
                .param("note", creditNoteId)
                .param("amount", amount)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clear() {
        for (String table : List.of(
                "fact_bill",
                "fact_sale_line",
                "fact_payment",
                "fact_expense",
                "fact_drawer",
                "fact_stock_movement",
                "variant_ref",
                "product_ref",
                "return_watch",
                "daily_summary")) {
            jdbc.sql("DELETE FROM reporting." + table + " WHERE tenant_id = :t")
                    .param("t", tenant())
                    .update();
        }
    }
}
