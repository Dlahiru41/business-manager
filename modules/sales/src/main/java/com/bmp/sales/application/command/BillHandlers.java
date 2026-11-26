package com.bmp.sales.application.command;

import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.sales.api.spi.CreditExposureProvider;
import com.bmp.sales.application.Approvals;
import com.bmp.sales.application.LinePricer;
import com.bmp.sales.application.SalesEvents;
import com.bmp.sales.application.SalesSettings;
import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.BillStatus;
import com.bmp.sales.domain.model.CreditNote;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.RefundTender;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.Shift;
import com.bmp.sales.domain.model.Tender;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.sales.domain.port.BillRepository;
import com.bmp.sales.domain.port.CreditNoteRepository;
import com.bmp.sales.domain.port.CustomerStore;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.sales.domain.port.ShiftRepository;
import com.bmp.sales.domain.service.BillNumbers;
import com.bmp.sales.domain.service.CreditCheck;
import com.bmp.sales.domain.service.DiscountPolicy;
import com.bmp.sales.domain.service.TenderValidator;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.security.CardNumbers;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bills (SAL-01..SAL-15): online through the API, where every breach is refused, and offline through sync, where the
 * bill already happened at the till and is accepted with flags. Completing publishes {@code SaleCompleted}.
 */
@Service
public class BillHandlers {

    private static final Logger log = LoggerFactory.getLogger(BillHandlers.class);

    private final BillRepository bills;
    private final CreditNoteRepository creditNotes;
    private final RegisterRepository registers;
    private final ShiftRepository shifts;
    private final ShiftHandlers shiftHandlers;
    private final CustomerStore customers;
    private final LinePricer pricer;
    private final Approvals approvals;
    private final CreditExposureProvider exposure;
    private final SalesSettings settings;
    private final SalesEvents events;
    private final NumberSequences numbers;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters") // the ports and services a bill's rules need
    public BillHandlers(
            BillRepository bills,
            CreditNoteRepository creditNotes,
            RegisterRepository registers,
            ShiftRepository shifts,
            ShiftHandlers shiftHandlers,
            CustomerStore customers,
            LinePricer pricer,
            Approvals approvals,
            CreditExposureProvider exposure,
            SalesSettings settings,
            SalesEvents events,
            @Qualifier("salesNumbers") NumberSequences numbers,
            IdGenerator ids,
            Clock clock) {
        this.bills = bills;
        this.creditNotes = creditNotes;
        this.registers = registers;
        this.shifts = shifts;
        this.shiftHandlers = shiftHandlers;
        this.customers = customers;
        this.pricer = pricer;
        this.approvals = approvals;
        this.exposure = exposure;
        this.settings = settings;
        this.events = events;
        this.numbers = numbers;
        this.ids = ids;
        this.clock = clock;
    }

    /** A tender as the till sent it. */
    public record TenderInput(
            UUID id,
            TenderMethod method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable String reference) {

        Tender tender() {
            return Tender.of(id, method, amount, tendered, reference);
        }
    }

    /**
     * A bill to create (draft) or submit (completed).
     *
     * @param totals the totals the till computed; required with {@code complete}
     * @param completedAt when the till completed it; offline required, online the server's time is used
     * @param cashierId offline the till's cashier; online the caller
     */
    public record SubmitBill(
            UUID id,
            String number,
            UUID registerId,
            @Nullable UUID shiftId,
            SalesChannel channel,
            @Nullable UUID customerId,
            List<LinePricer.LineInput> lines,
            @Nullable BillDiscount discount,
            boolean complete,
            List<TenderInput> tenders,
            @Nullable Totals totals,
            @Nullable Instant completedAt,
            @Nullable UUID cashierId,
            @Nullable UUID deviceId,
            @Nullable UUID creditApprovedBy) {

        public SubmitBill {
            lines = List.copyOf(lines);
            tenders = List.copyOf(tenders);
        }
    }

    /** The bill and whether it was already known (the till resent it, SAL-01). */
    public record Outcome(Bill bill, boolean replayed) {}

    /** Rules SAL-01, SAL-02: creates a draft or takes a completed bill; the same id again replays. */
    @Transactional
    @Observed(name = "bmp.sales.submit-bill")
    public Outcome submit(SubmitBill c, Mode mode) {
        Optional<Bill> known = bills.findById(c.id());
        if (known.isPresent()) {
            known.get().lines();
            known.get().tenders();
            return new Outcome(known.get(), true);
        }
        if (bills.findByNumber(c.number()).isPresent()) {
            throw new BusinessRuleViolation(SalesErrorCode.BILL_NUMBER_CONFLICT, Map.of("number", c.number()));
        }
        Register register = registers
                .lock(c.registerId())
                .filter(Register::isActive)
                .orElseThrow(() -> new BusinessRuleViolation(
                        SalesErrorCode.UNKNOWN_REGISTER, Map.of("registerId", c.registerId())));
        long sequence = sequence(c.number(), register);
        CustomerStore.Customer customer = customer(c.customerId(), c.channel(), mode);
        if (!mode.online() && (!c.complete() || c.completedAt() == null)) {
            throw invalid("An offline bill arrives completed, with its completion time", "completedAt");
        }
        Instant at = mode.online() ? clock.instant() : Objects.requireNonNull(c.completedAt());
        Set<BillFlag> flags = EnumSet.noneOf(BillFlag.class);
        UUID shiftId = shift(register.id(), c.shiftId(), at, mode, flags);
        LinePricer.Priced priced = pricer.price(c.lines(), c.channel(), customer, at, mode);
        flags.addAll(priced.flags());
        UUID cashier = mode.online() ? currentUser() : c.cashierId();
        Bill bill = Bill.draft(
                new Bill.Header(
                        c.id(),
                        TenantContext.require(),
                        c.number(),
                        register.id(),
                        shiftId,
                        register.locationId(),
                        c.deviceId() != null ? c.deviceId() : register.deviceId(),
                        c.channel(),
                        customer == null ? null : customer.partyId(),
                        priced.priceListId(),
                        priced.priceListVersion(),
                        settings.currency(),
                        cashier,
                        !mode.online()),
                priced.lines(),
                c.discount(),
                settings.rounding());
        discountLimit(bill, priced.discountApproved(), c.discount(), mode).ifPresent(flags::add);
        if (!c.complete()) {
            Bill saved = bills.saveAndFlush(bill);
            saved.lines();
            saved.tenders();
            return new Outcome(saved, false);
        }
        if (c.totals() == null) {
            throw invalid("A completed bill carries the totals the till computed", "totals");
        }
        Bill saved = finish(
                bill,
                register,
                sequence,
                c.tenders(),
                c.totals(),
                at,
                shiftId,
                cashier,
                customer,
                c.creditApprovedBy(),
                mode,
                flags);
        return new Outcome(saved, false);
    }

    /** A change to a draft (SAL-02): everything is re-priced. */
    public record UpdateBill(
            @Nullable SalesChannel channel,
            @Nullable UUID customerId,
            @Nullable List<LinePricer.LineInput> lines,
            @Nullable BillDiscount discount) {}

    @Transactional
    @Observed(name = "bmp.sales.update-bill")
    public Bill update(UUID billId, long expectedVersion, UpdateBill u) {
        Bill bill = bills.lock(billId).orElseThrow(() -> notFound(billId));
        IfMatch.check(expectedVersion, bill.version());
        if (bill.status() != BillStatus.DRAFT) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.BILL_STATE_INVALID,
                    Map.of("status", bill.status().name()));
        }
        SalesChannel channel = u.channel() != null ? u.channel() : bill.channel();
        UUID customerId = u.customerId() != null ? u.customerId() : bill.customerId();
        CustomerStore.Customer customer = customer(customerId, channel, Mode.ONLINE);
        List<LinePricer.LineInput> lines = u.lines() != null
                ? u.lines()
                : bill.lines().stream()
                        .map(l -> new LinePricer.LineInput(
                                l.id(),
                                l.variantId(),
                                l.packagingUnitId(),
                                l.qty(),
                                l.weighed(),
                                null,
                                l.lineDiscount().subtract(l.ruleDiscount()),
                                null,
                                null,
                                null))
                        .toList();
        BillDiscount discount = u.discount() != null ? u.discount() : bill.discount();
        LinePricer.Priced priced = pricer.price(lines, channel, customer, clock.instant(), Mode.ONLINE);
        bill.reprice(
                channel,
                customer == null ? null : customer.partyId(),
                priced.priceListId(),
                priced.priceListVersion(),
                priced.lines(),
                discount,
                settings.rounding());
        discountLimit(bill, priced.discountApproved(), discount, Mode.ONLINE);
        Bill saved = bills.saveAndFlush(bill);
        saved.lines();
        saved.tenders();
        return saved;
    }

    /** Rules SAL-04..SAL-07, SAL-12: completes a draft online. */
    @Transactional
    @Observed(name = "bmp.sales.complete-bill")
    public Bill complete(UUID billId, List<TenderInput> tenders, Totals totals, @Nullable UUID creditApprovedBy) {
        Bill bill = bills.lock(billId).orElseThrow(() -> notFound(billId));
        if (bill.status() != BillStatus.DRAFT) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.BILL_STATE_INVALID,
                    Map.of("status", bill.status().name()));
        }
        Register register = registers.lock(bill.registerId()).orElseThrow();
        Instant at = clock.instant();
        Set<BillFlag> flags = EnumSet.noneOf(BillFlag.class);
        UUID shiftId = shift(register.id(), null, at, Mode.ONLINE, flags);
        CustomerStore.Customer customer = bill.customerId() == null
                ? null
                : customers.find(Objects.requireNonNull(bill.customerId())).orElse(null);
        return finish(
                bill,
                register,
                sequence(bill.number(), register),
                tenders,
                totals,
                at,
                shiftId,
                currentUser(),
                customer,
                creditApprovedBy,
                Mode.ONLINE,
                flags);
    }

    @SuppressWarnings("TooManyParameters") // the completion's inputs
    private Bill finish(
            Bill bill,
            Register register,
            long sequence,
            List<TenderInput> tenderInputs,
            Totals totals,
            Instant at,
            UUID shiftId,
            @Nullable UUID cashier,
            CustomerStore.@Nullable Customer customer,
            @Nullable UUID creditApprovedBy,
            Mode mode,
            Set<BillFlag> flags) {
        List<Tender> tenders = tenderInputs.stream().map(TenderInput::tender).toList();
        BigDecimal credit = tenders.stream()
                .filter(t -> t.method() == TenderMethod.CREDIT)
                .map(Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (credit.signum() > 0) {
            creditRules(credit, customer, creditApprovedBy, mode, flags);
        }
        if (!mode.online() && Duration.between(at, clock.instant()).toHours() >= settings.offlineMaxHours()) {
            flags.add(BillFlag.LATE_SYNC);
        }
        bill.complete(new Bill.Completion(
                tenders,
                totals,
                at,
                settings.businessDate(at),
                shiftId,
                cashier,
                mode,
                new TenderValidator.Rules(
                        settings.tenderReferenceRequired(),
                        customer == null ? null : customer.partyId(),
                        customer != null && customer.creditAllowed()),
                flags));
        register.accepted(sequence);
        registers.saveAndFlush(register);
        Bill saved = bills.saveAndFlush(bill);
        saved.lines();
        saved.tenders();
        events.completed(saved);
        log.atInfo()
                .addKeyValue("billId", saved.id())
                .addKeyValue("number", saved.number())
                .addKeyValue("registerId", saved.registerId())
                .addKeyValue("grandTotal", saved.payableTotals().grandTotal())
                .addKeyValue("offline", saved.offline())
                .addKeyValue("flags", saved.flags())
                .log("Bill completed");
        if (!saved.flags().isEmpty()) {
            log.atWarn()
                    .addKeyValue("billId", saved.id())
                    .addKeyValue("flags", saved.flags())
                    .log("Bill accepted with flags");
        }
        return saved;
    }

    /** Rule SAL-07: credit sales need the tenant to allow them and the customer's limit (or an override). */
    private void creditRules(
            BigDecimal credit,
            CustomerStore.@Nullable Customer customer,
            @Nullable UUID creditApprovedBy,
            Mode mode,
            Set<BillFlag> flags) {
        if (!settings.allowCreditSales()) {
            if (mode.online()) {
                throw new BusinessRuleViolation(SalesErrorCode.CREDIT_NOT_ALLOWED, Map.of("allowCreditSales", false));
            }
            flags.add(BillFlag.CREDIT_LIMIT_EXCEEDED);
            return;
        }
        if (customer == null || !customer.creditAllowed()) {
            return; // the tender validator refuses (online) or flags (offline)
        }
        BigDecimal owed = exposure.exposureOf(PartyId.of(customer.partyId()));
        boolean over = owed.add(credit).compareTo(customer.creditLimit()) > 0;
        boolean approved = over && approvals.approved("sales:credit-override", creditApprovedBy, mode);
        CreditCheck.check(owed, credit, customer.creditLimit(), approved, mode).ifPresent(flags::add);
    }

    /** Rule SAL-05: what a cashier took off beyond the limit needs a manager. */
    private Optional<BillFlag> discountLimit(
            Bill bill, boolean lineApproved, @Nullable BillDiscount discount, Mode mode) {
        BigDecimal cashier = bill.cashierDiscount();
        if (cashier.signum() == 0) {
            return Optional.empty();
        }
        boolean billApproved = discount != null
                && discount.approvedBy() != null
                && approvals.approved("sales:discount-unlimited", discount.approvedBy(), mode);
        boolean approved = lineApproved
                || billApproved
                || (mode.online() && approvals.approved("sales:discount-unlimited", null, mode));
        return DiscountPolicy.check(
                cashier, bill.totals().subtotal(), settings.maxCashierDiscountPct(), approved, mode);
    }

    /** Rule SAL-09: voids a bill on its business date; a manager approves when the tenant says so. */
    @Transactional
    @Observed(name = "bmp.sales.void-bill")
    public Bill voidBill(UUID billId, String reason, @Nullable UUID approvedBy, @Nullable Instant voidedAt, Mode mode) {
        Bill bill = bills.lock(billId).orElseThrow(() -> notFound(billId));
        if (bill.status() == BillStatus.VOIDED && !mode.online()) {
            return bill; // a till resending its void
        }
        if (settings.voidRequiresManager()) {
            if (approvedBy == null) {
                throw new BusinessRuleViolation(SalesErrorCode.APPROVAL_REQUIRED, Map.of("permission", "sales:void"));
            }
            if (!approvals.approved("sales:void", approvedBy, mode)) {
                throw new BusinessRuleViolation(
                        SalesErrorCode.APPROVER_NOT_ALLOWED, Map.of("permission", "sales:void"));
            }
        }
        Instant at = voidedAt != null && !mode.online() ? voidedAt : clock.instant();
        bill.voidBill(reason, approvedBy, currentUser(), at, settings.businessDate(at));
        Bill saved = bills.saveAndFlush(bill);
        saved.lines();
        saved.tenders();
        events.voided(saved);
        log.atInfo()
                .addKeyValue("billId", saved.id())
                .addKeyValue("number", saved.number())
                .log("Bill voided");
        return saved;
    }

    /** A refund the caller asked for (SAL-10). */
    public record RefundInput(
            TenderMethod method,
            BigDecimal amount,
            @Nullable String reference) {}

    /** A return (SAL-10). */
    public record ReturnBill(
            UUID creditNoteId,
            String reason,
            List<Bill.ReturnLine> lines,
            List<RefundInput> refunds,
            @Nullable UUID approvedBy,
            @Nullable Instant returnedAt) {

        public ReturnBill {
            lines = List.copyOf(lines);
            refunds = List.copyOf(refunds);
        }
    }

    /** Rule SAL-10: a credit note for returned lines; the same credit note id again replays. */
    @Transactional
    @Observed(name = "bmp.sales.return-bill")
    public CreditNote returnBill(UUID billId, ReturnBill r, Mode mode) {
        Optional<CreditNote> known = creditNotes.findById(r.creditNoteId());
        if (known.isPresent()) {
            known.get().lines();
            known.get().refundTenders();
            return known.get();
        }
        for (RefundInput refund : r.refunds()) {
            if (CardNumbers.containsCardNumber(refund.reference())) {
                throw new BusinessRuleViolation(SalesErrorCode.CARD_DATA_NOT_ALLOWED, Map.of());
            }
        }
        Bill bill = bills.lock(billId).orElseThrow(() -> notFound(billId));
        Instant at = r.returnedAt() != null && !mode.online() ? r.returnedAt() : clock.instant();
        LocalDate today = settings.businessDate(at);
        int window = settings.returnWindowDays();
        LocalDate soldOn = bill.businessDate();
        boolean late = soldOn != null && ChronoUnit.DAYS.between(soldOn, today) > window;
        boolean override = late && approvals.approved("sales:return-override", r.approvedBy(), mode);
        String number = settings.creditNotePrefix() + "%06d".formatted(numbers.next("CREDIT_NOTE"));
        CreditNote note = bill.returnLines(
                r.creditNoteId(),
                number,
                r.reason(),
                r.lines(),
                r.refunds().stream()
                        .map(f -> new RefundTender(ids.newUuid(), f.method(), f.amount(), f.reference(), null))
                        .toList(),
                today,
                window,
                override,
                r.approvedBy(),
                at,
                settings.rounding(),
                ids::newUuid);
        bills.saveAndFlush(bill);
        CreditNote saved = creditNotes.saveAndFlush(note);
        saved.lines();
        saved.refundTenders();
        events.returned(bill, saved);
        log.atInfo()
                .addKeyValue("billId", bill.id())
                .addKeyValue("creditNoteId", saved.id())
                .addKeyValue("total", saved.total())
                .log("Bill returned");
        return saved;
    }

    /** Rule SAL-01: the number is {@code <the register's prefix>-<6 digits>}. */
    private static long sequence(String number, Register register) {
        return BillNumbers.parse(number)
                .filter(n -> n.prefix().equals(register.billPrefix()))
                .map(BillNumbers.BillNumber::sequence)
                .orElseThrow(() -> invalid("The number is the register's prefix and 6 digits", "number"));
    }

    private CustomerStore.@Nullable Customer customer(@Nullable UUID customerId, SalesChannel channel, Mode mode) {
        if (customerId == null) {
            if (mode.online() && channel == SalesChannel.WHOLESALE && settings.wholesaleRequiresCustomer()) {
                throw new BusinessRuleViolation(SalesErrorCode.CUSTOMER_REQUIRED, Map.of());
            }
            return null;
        }
        return customers
                .find(customerId)
                .filter(c -> c.active() && c.isCustomer())
                .orElseThrow(() ->
                        new BusinessRuleViolation(SalesErrorCode.UNKNOWN_CUSTOMER, Map.of("customerId", customerId)));
    }

    /**
     * Rule SAL-12: online bills need the register's OPEN shift; offline ones attach to the shift they name, the shift
     * running at their time, or the register's synthetic offline shift (flagged).
     */
    private UUID shift(UUID registerId, @Nullable UUID shiftId, Instant at, Mode mode, Set<BillFlag> flags) {
        if (mode.online()) {
            Shift open = shifts.findOpen(registerId)
                    .filter(s -> shiftId == null || s.id().equals(shiftId))
                    .orElseThrow(() ->
                            new BusinessRuleViolation(SalesErrorCode.SHIFT_NOT_OPEN, Map.of("registerId", registerId)));
            return open.id();
        }
        if (shiftId != null) {
            Optional<Shift> named =
                    shifts.findById(shiftId).filter(s -> s.registerId().equals(registerId));
            if (named.isPresent()) {
                return named.get().id();
            }
        }
        Optional<Shift> covering = shifts.findCovering(registerId, at);
        if (covering.isPresent()) {
            return covering.get().id();
        }
        flags.add(BillFlag.OFFLINE_SHIFT);
        return shiftHandlers.offlineShift(registerId, at).id();
    }

    private static @Nullable UUID currentUser() {
        var user = ActorContext.currentOrSystem().userId();
        return user == null ? null : user.value();
    }

    private static NotFoundException notFound(UUID billId) {
        return new NotFoundException(SalesErrorCode.BILL_NOT_FOUND, Map.of("billId", billId));
    }

    private static BusinessRuleViolation invalid(String message, String field) {
        return new BusinessRuleViolation(PlatformErrorCode.VALIDATION_FAILED, message, Map.of("field", field));
    }
}
