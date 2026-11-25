package com.bmp.sales.domain.model;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.service.BillTotalsCalculator;
import com.bmp.sales.domain.service.ReturnAmounts;
import com.bmp.sales.domain.service.TenderValidator;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.RoundingPolicy;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A sale document (SAL-01..SAL-15). A DRAFT is edited and completed online; a bill can also arrive already completed
 * (online submission or sync, SAL-02). Totals are always recomputed here (SAL-04). A completed bill never changes
 * (SAL-08): it is voided on its business date (SAL-09) or lines are returned with a credit note (SAL-10).
 */
@Entity
@Table(schema = "sales", name = "bill")
public class Bill implements Auditable {

    /** Offline totals within this of the server's are not a mismatch (SAL-04). */
    public static final BigDecimal TOTALS_TOLERANCE = new BigDecimal("0.01");

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "register_id", nullable = false, updatable = false)
    private UUID registerId;

    @Column(name = "shift_id")
    private @Nullable UUID shiftId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "device_id", updatable = false)
    private @Nullable UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SalesChannel channel;

    @Column(name = "customer_id")
    private @Nullable UUID customerId;

    @Column(name = "price_list_id")
    private @Nullable UUID priceListId;

    @Column(name = "price_list_version")
    private @Nullable Integer priceListVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "bill_id", nullable = false, updatable = false)
    @OrderColumn(name = "line_no")
    private List<BillLine> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "bill_id", nullable = false, updatable = false)
    @OrderColumn(name = "tender_no")
    private List<BillTender> tenders = new ArrayList<>();

    @Embedded
    private @Nullable BillDiscount discount;

    @Column(name = "bill_discount_amount", nullable = false)
    private BigDecimal billDiscountAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Embedded
    @AttributeOverride(name = "subtotal", column = @Column(name = "device_subtotal"))
    @AttributeOverride(name = "discountTotal", column = @Column(name = "device_discount_total"))
    @AttributeOverride(name = "taxTotal", column = @Column(name = "device_tax_total"))
    @AttributeOverride(name = "grandTotal", column = @Column(name = "device_grand_total"))
    private @Nullable DeviceTotals deviceTotals;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "business_date")
    private @Nullable LocalDate businessDate;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Column(name = "cashier_id")
    private @Nullable UUID cashierId;

    @Column(nullable = false)
    private boolean offline;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] flags = new String[0];

    @Column(name = "void_reason")
    private @Nullable String voidReason;

    @Column(name = "voided_at")
    private @Nullable Instant voidedAt;

    @Column(name = "voided_by")
    private @Nullable UUID voidedBy;

    @Column(name = "void_approved_by")
    private @Nullable UUID voidApprovedBy;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Bill() {}

    /**
     * What a bill is about, fixed when it is created.
     *
     * @param number {@code <registerPrefix>-<6-digit sequence>} (SAL-01)
     */
    public record Header(
            UUID id,
            TenantId tenant,
            String number,
            UUID registerId,
            @Nullable UUID shiftId,
            UUID locationId,
            @Nullable UUID deviceId,
            SalesChannel channel,
            @Nullable UUID customerId,
            @Nullable UUID priceListId,
            @Nullable Integer priceListVersion,
            String currency,
            @Nullable UUID cashierId,
            boolean offline) {}

    /** The device's totals, kept when they differ from the server's (SAL-04). */
    @jakarta.persistence.Embeddable
    public record DeviceTotals(
            BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal) {

        static DeviceTotals of(Totals totals) {
            return new DeviceTotals(totals.subtotal(), totals.discountTotal(), totals.taxTotal(), totals.grandTotal());
        }

        public Totals totals() {
            return new Totals(subtotal, discountTotal, taxTotal, grandTotal);
        }
    }

    /**
     * How a bill is completed.
     *
     * @param claimedTotals the totals the till computed; online they must equal the server's, offline they are
     *     authoritative and kept when they differ by more than {@link #TOTALS_TOLERANCE}
     * @param flags what the caller already found (price, discount, credit, shift, late sync)
     */
    public record Completion(
            List<Tender> tenders,
            Totals claimedTotals,
            Instant completedAt,
            LocalDate businessDate,
            @Nullable UUID shiftId,
            @Nullable UUID cashierId,
            Mode mode,
            TenderValidator.Rules tenderRules,
            Set<BillFlag> flags) {

        public Completion {
            tenders = List.copyOf(tenders);
            flags = Set.copyOf(flags);
        }
    }

    public static Bill draft(
            Header header, List<LineDraft> lines, @Nullable BillDiscount discount, RoundingPolicy rounding) {
        Bill bill = new Bill();
        bill.id = Objects.requireNonNull(header.id(), "id");
        bill.tenantId = header.tenant().value();
        bill.number = header.number();
        bill.registerId = header.registerId();
        bill.shiftId = header.shiftId();
        bill.locationId = header.locationId();
        bill.deviceId = header.deviceId();
        bill.channel = header.channel();
        bill.customerId = header.customerId();
        bill.priceListId = header.priceListId();
        bill.priceListVersion = header.priceListVersion();
        bill.currency = header.currency();
        bill.cashierId = header.cashierId();
        bill.offline = header.offline();
        bill.status = BillStatus.DRAFT;
        bill.price(lines, discount, rounding);
        return bill;
    }

    /** Re-prices a draft: new lines, discount, channel or customer (SAL-02). */
    public void reprice(
            SalesChannel newChannel,
            @Nullable UUID newCustomer,
            @Nullable UUID newPriceListId,
            @Nullable Integer newPriceListVersion,
            List<LineDraft> newLines,
            @Nullable BillDiscount newDiscount,
            RoundingPolicy rounding) {
        require(BillStatus.DRAFT);
        this.channel = newChannel;
        this.customerId = newCustomer;
        this.priceListId = newPriceListId;
        this.priceListVersion = newPriceListVersion;
        price(newLines, newDiscount, rounding);
    }

    /** Rules SAL-04, SAL-06, SAL-07: settles the bill; it never changes afterwards (SAL-08). */
    public void complete(Completion c) {
        require(BillStatus.DRAFT);
        Totals computed = totals();
        Set<BillFlag> found = EnumSet.noneOf(BillFlag.class);
        found.addAll(c.flags());
        boolean differ = c.claimedTotals().differsFrom(computed, BigDecimal.ZERO);
        if (differ && c.mode().online()) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.TOTALS_MISMATCH,
                    Map.of(
                            "grandTotal", computed.grandTotal().toPlainString(),
                            "claimedGrandTotal", c.claimedTotals().grandTotal().toPlainString()));
        }
        if (differ) {
            this.deviceTotals = DeviceTotals.of(c.claimedTotals());
            if (c.claimedTotals().differsFrom(computed, TOTALS_TOLERANCE)) {
                found.add(BillFlag.TOTALS_MISMATCH);
            }
        }
        // Offline the customer paid what the till said: tenders are checked against its total.
        BigDecimal payable =
                c.mode().online() ? computed.grandTotal() : c.claimedTotals().grandTotal();
        found.addAll(TenderValidator.validate(c.tenders(), payable, c.tenderRules(), c.mode()));
        UUID tenant = tenantId;
        this.tenders.clear();
        c.tenders().forEach(t -> tenders.add(new BillTender(tenant, t)));
        this.status = BillStatus.COMPLETED;
        this.completedAt = c.completedAt();
        this.businessDate = c.businessDate();
        if (c.shiftId() != null) {
            this.shiftId = c.shiftId();
        }
        if (c.cashierId() != null) {
            this.cashierId = c.cashierId();
        }
        this.flags = found.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    /**
     * Rule SAL-09: a completed bill without returns is voided on its business date; afterwards it must be returned.
     *
     * @param today the tenant's business date now
     * @param approvedBy required when the tenant's {@code voidRequiresManager} is set (checked by the caller)
     */
    public void voidBill(String reason, @Nullable UUID approvedBy, @Nullable UUID by, Instant at, LocalDate today) {
        require(BillStatus.COMPLETED);
        if (!today.equals(businessDate)) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.VOID_WINDOW_PASSED, Map.of("businessDate", String.valueOf(businessDate)));
        }
        this.status = BillStatus.VOIDED;
        this.voidReason = reason;
        this.voidApprovedBy = approvedBy;
        this.voidedBy = by;
        this.voidedAt = at;
    }

    /** A returned quantity of one line. */
    public record ReturnLine(UUID billLineId, BigDecimal qty, boolean restock) {}

    /**
     * Rule SAL-10: returns lines within the window (or with an override), each at most what is left of it, for its
     * pro-rata share of the line total; refunds default to the original method, cash when there were several.
     *
     * @param refunds the refund tenders asked for; empty for the defaults
     * @param overrideApproved whether {@code sales:return-override} allows a return after the window
     */
    @SuppressWarnings("TooManyParameters") // a return's document fields and the rules it is checked against
    public CreditNote returnLines(
            UUID creditNoteId,
            String creditNoteNumber,
            String reason,
            List<ReturnLine> returned,
            List<RefundTender> refunds,
            LocalDate today,
            int returnWindowDays,
            boolean overrideApproved,
            @Nullable UUID approvedBy,
            Instant at,
            RoundingPolicy rounding,
            Supplier<UUID> ids) {
        if (status != BillStatus.COMPLETED && status != BillStatus.PARTIALLY_RETURNED) {
            throw invalidState();
        }
        LocalDate soldOn = Objects.requireNonNull(businessDate, "businessDate");
        if (ChronoUnit.DAYS.between(soldOn, today) > returnWindowDays && !overrideApproved) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.RETURN_WINDOW_PASSED, Map.of("returnWindowDays", returnWindowDays));
        }
        Map<UUID, BigDecimal> asked = new HashMap<>();
        returned.forEach(r -> asked.merge(r.billLineId(), r.qty(), BigDecimal::add));
        List<CreditNoteLine> creditLines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (ReturnLine r : returned) {
            BillLine line = line(r.billLineId());
            if (r.qty().signum() <= 0
                    || asked.getOrDefault(r.billLineId(), r.qty()).compareTo(line.remainingQty()) > 0) {
                throw new BusinessRuleViolation(
                        SalesErrorCode.RETURN_QTY_EXCEEDED,
                        Map.of(
                                "billLineId",
                                r.billLineId(),
                                "remaining",
                                line.remainingQty().toPlainString()));
            }
            ReturnAmounts.Share share = ReturnAmounts.of(
                    r.qty(),
                    line.qty(),
                    line.returnedQty(),
                    line.lineTotal(),
                    line.taxAmount(),
                    line.returnedAmount(),
                    line.returnedTax(),
                    rounding);
            BigDecimal qtyBase = line.qtyBase().multiply(r.qty()).divide(line.qty(), 6, java.math.RoundingMode.HALF_UP);
            line.returned(r.qty(), share.amount(), share.tax());
            creditLines.add(new CreditNoteLine(
                    ids.get(), tenantId, line, r.qty(), qtyBase, share.amount(), share.tax(), r.restock()));
            total = total.add(share.amount());
            tax = tax.add(share.tax());
        }
        List<RefundTender> plan = refunds.isEmpty() ? defaultRefund(total, ids) : refunds;
        BigDecimal planned = plan.stream().map(RefundTender::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (planned.compareTo(total) != 0) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.REFUND_TENDER_MISMATCH,
                    Map.of("refunds", planned.toPlainString(), "total", total.toPlainString()));
        }
        this.status = lines.stream().allMatch(l -> l.remainingQty().signum() == 0)
                ? BillStatus.RETURNED
                : BillStatus.PARTIALLY_RETURNED;
        return new CreditNote(
                creditNoteId, tenantId, creditNoteNumber, id, reason, total, tax, creditLines, plan, approvedBy, at);
    }

    /** Rule SAL-10: the original method, cash when the bill had several. */
    private List<RefundTender> defaultRefund(BigDecimal total, Supplier<UUID> ids) {
        Set<TenderMethod> methods = new LinkedHashSet<>();
        tenders.forEach(t -> methods.add(t.method()));
        boolean single = methods.size() == 1;
        TenderMethod method = single ? methods.iterator().next() : TenderMethod.CASH;
        return List.of(new RefundTender(
                ids.get(), method, total, null, single ? tenders.getFirst().id() : null));
    }

    private void price(List<LineDraft> newLines, @Nullable BillDiscount newDiscount, RoundingPolicy rounding) {
        if (newLines.isEmpty()) {
            throw new IllegalArgumentException("A bill has at least one line");
        }
        BillTotalsCalculator.Result result = BillTotalsCalculator.calculate(
                newLines.stream()
                        .map(l -> new BillTotalsCalculator.PricedLine(
                                l.id(), l.qty(), l.unitPrice(), l.lineDiscount(), l.taxInclusive(), l.rates()))
                        .toList(),
                newDiscount,
                CurrencyCode.of(currency),
                rounding);
        lines.clear();
        for (int i = 0; i < newLines.size(); i++) {
            BillTotalsCalculator.LineAmounts a = result.lines().get(i);
            lines.add(new BillLine(tenantId, newLines.get(i), a.allocatedDiscount(), a.tax(), a.lineTotal()));
        }
        this.discount = newDiscount;
        this.billDiscountAmount = result.billDiscount();
        Totals t = result.totals();
        this.subtotal = t.subtotal();
        this.discountTotal = t.discountTotal();
        this.taxTotal = t.taxTotal();
        this.grandTotal = t.grandTotal();
    }

    private BillLine line(UUID lineId) {
        return lines.stream()
                .filter(l -> l.id().equals(lineId))
                .findFirst()
                .orElseThrow(() ->
                        new BusinessRuleViolation(SalesErrorCode.UNKNOWN_BILL_LINE, Map.of("billLineId", lineId)));
    }

    private void require(BillStatus expected) {
        if (status != expected) {
            throw invalidState();
        }
    }

    private BusinessRuleViolation invalidState() {
        return new BusinessRuleViolation(SalesErrorCode.BILL_STATE_INVALID, Map.of("status", status.name()));
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public Totals totals() {
        return new Totals(subtotal, discountTotal, taxTotal, grandTotal);
    }

    public @Nullable Totals deviceTotals() {
        return deviceTotals == null ? null : deviceTotals.totals();
    }

    public Set<BillFlag> flags() {
        Set<BillFlag> set = EnumSet.noneOf(BillFlag.class);
        Arrays.stream(flags).map(BillFlag::valueOf).forEach(set::add);
        return set;
    }

    /** CREDIT tenders of the bill: what the customer owes for it (SAL-07). */
    public BigDecimal creditAmount() {
        return tenders.stream()
                .filter(t -> t.method() == TenderMethod.CREDIT)
                .map(BillTender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID registerId() {
        return registerId;
    }

    public @Nullable UUID shiftId() {
        return shiftId;
    }

    public UUID locationId() {
        return locationId;
    }

    public @Nullable UUID deviceId() {
        return deviceId;
    }

    public SalesChannel channel() {
        return channel;
    }

    public @Nullable UUID customerId() {
        return customerId;
    }

    public @Nullable UUID priceListId() {
        return priceListId;
    }

    public @Nullable Integer priceListVersion() {
        return priceListVersion;
    }

    public BillStatus status() {
        return status;
    }

    public List<BillLine> lines() {
        return List.copyOf(lines);
    }

    public List<BillTender> tenders() {
        return List.copyOf(tenders);
    }

    public @Nullable BillDiscount discount() {
        return discount;
    }

    public BigDecimal billDiscountAmount() {
        return billDiscountAmount;
    }

    public String currency() {
        return currency;
    }

    public @Nullable LocalDate businessDate() {
        return businessDate;
    }

    public @Nullable Instant completedAt() {
        return completedAt;
    }

    public @Nullable UUID cashierId() {
        return cashierId;
    }

    public boolean offline() {
        return offline;
    }

    public @Nullable String voidReason() {
        return voidReason;
    }

    public @Nullable Instant voidedAt() {
        return voidedAt;
    }

    public @Nullable UUID voidApprovedBy() {
        return voidApprovedBy;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public AuditStamps audit() {
        return audit;
    }
}
