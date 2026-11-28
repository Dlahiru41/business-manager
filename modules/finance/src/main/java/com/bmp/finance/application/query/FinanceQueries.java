package com.bmp.finance.application.query;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountStatus;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.CashDrop;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.Expense;
import com.bmp.finance.domain.model.ExpenseCategory;
import com.bmp.finance.domain.model.FiscalPeriod;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PaidFrom;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.SupplierPayment;
import com.bmp.finance.domain.port.AccountRepository;
import com.bmp.finance.domain.port.DocumentLedger;
import com.bmp.finance.domain.port.ExpenseCategoryRepository;
import com.bmp.finance.domain.port.LedgerReader;
import com.bmp.finance.domain.port.PartyAccounts;
import com.bmp.finance.domain.port.PeriodStore;
import com.bmp.finance.domain.service.Aging;
import com.bmp.finance.domain.service.ProfitAndLoss;
import com.bmp.shared.error.NotFoundException;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models behind the finance API. Pages fetch one row more than asked, to know whether another follows. */
@Service
@Transactional(readOnly = true)
public class FinanceQueries {

    private final AccountRepository accounts;
    private final ExpenseCategoryRepository categories;
    private final LedgerReader ledger;
    private final DocumentLedger documents;
    private final PartyAccounts parties;
    private final PeriodStore periods;
    private final FinanceSettings settings;

    public FinanceQueries(
            AccountRepository accounts,
            ExpenseCategoryRepository categories,
            LedgerReader ledger,
            DocumentLedger documents,
            PartyAccounts parties,
            PeriodStore periods,
            FinanceSettings settings) {
        this.accounts = accounts;
        this.categories = categories;
        this.ledger = ledger;
        this.documents = documents;
        this.parties = parties;
        this.periods = periods;
        this.settings = settings;
    }

    public List<Account> accounts(
            @Nullable AccountType type, @Nullable AccountStatus status, @Nullable String after, int fetch) {
        return accounts.page(type, status, after, fetch);
    }

    /** What journals are searched by. */
    public record JournalSearch(
            @Nullable Period period,
            com.bmp.finance.domain.model.@Nullable SourceType sourceType,
            @Nullable UUID sourceId,
            @Nullable UUID accountId,
            @Nullable LocalDate from,
            @Nullable LocalDate to) {}

    /** Journals newest first; the cursor is the last entry date and id. */
    public PageResponse<LedgerReader.JournalView> journals(JournalSearch search, PageQuery query) {
        LedgerReader.Cursor after = null;
        String cursor = query.cursor();
        if (cursor != null) {
            List<String> values = CursorCodec.decode(cursor, 2);
            after = new LedgerReader.Cursor(LocalDate.parse(values.get(0)), UUID.fromString(values.get(1)));
        }
        var filter = new LedgerReader.Filter(
                search.period(),
                search.sourceType(),
                search.sourceId(),
                search.accountId(),
                search.from(),
                search.to());
        return PageResponse.of(
                ledger.page(filter, after, query.fetchSize()),
                query,
                j -> List.of(j.entryDate().toString(), j.id().toString()));
    }

    public LedgerReader.JournalView journal(UUID id) {
        return ledger.view(id)
                .orElseThrow(() -> new NotFoundException(FinanceErrorCode.JOURNAL_NOT_FOUND, Map.of("journalId", id)));
    }

    /** A trial balance: of one period's journals, or of everything up to {@code asOf} (default today). */
    public TrialBalance trialBalance(@Nullable LocalDate asOf, @Nullable Period period) {
        List<LedgerReader.AccountTotal> rows = period != null
                ? ledger.totalsOf(period, period, false)
                : ledger.totalsUpTo(asOf != null ? asOf : settings.today());
        BigDecimal debit = rows.stream().map(LedgerReader.AccountTotal::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit =
                rows.stream().map(LedgerReader.AccountTotal::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TrialBalance(
                period == null ? (asOf != null ? asOf : settings.today()) : null,
                period,
                rows,
                debit,
                credit,
                settings.currency());
    }

    public record TrialBalance(
            @Nullable LocalDate asOf,
            @Nullable Period period,
            List<LedgerReader.AccountTotal> rows,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            String currency) {}

    /** Profit and loss over periods, year-end closing entries left out (posting-rules.md §4). */
    public ProfitAndLossReport profitAndLoss(Period from, Period to) {
        List<LedgerReader.AccountTotal> totals = ledger.totalsOf(from, to, true).stream()
                .filter(t -> t.type().isProfitAndLoss())
                .toList();
        ProfitAndLoss pl = ProfitAndLoss.of(totals.stream()
                .map(t -> new ProfitAndLoss.Movement(t.systemKey(), t.type(), t.debit(), t.credit()))
                .toList());
        return new ProfitAndLossReport(from, to, pl, totals, settings.currency());
    }

    public record ProfitAndLossReport(
            Period from, Period to, ProfitAndLoss result, List<LedgerReader.AccountTotal> accounts, String currency) {}

    public List<ExpenseCategory> categories(@Nullable String after, int fetch) {
        return categories.page(after, fetch);
    }

    public List<Expense> expenses(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable UUID categoryId,
            @Nullable PaidFrom paidFrom,
            DocumentLedger.@Nullable Cursor after,
            int fetch) {
        return documents.expenses(from, to, categoryId, paidFrom, after, fetch);
    }

    public Expense expense(UUID id) {
        return documents
                .expense(id)
                .orElseThrow(() -> new NotFoundException(FinanceErrorCode.EXPENSE_NOT_FOUND, Map.of("expenseId", id)));
    }

    public List<SupplierPayment> supplierPayments(@Nullable UUID supplierId, @Nullable UUID beforeId, int fetch) {
        return documents.supplierPayments(supplierId, beforeId, fetch);
    }

    public SupplierPayment supplierPayment(UUID id) {
        return documents
                .supplierPayment(id)
                .orElseThrow(() ->
                        new NotFoundException(FinanceErrorCode.SUPPLIER_PAYMENT_NOT_FOUND, Map.of("paymentId", id)));
    }

    public List<CashDrop> cashDrops(@Nullable UUID sessionId, @Nullable UUID beforeId, int fetch) {
        return documents.cashDrops(sessionId, beforeId, fetch);
    }

    /** Rule FIN-07: a page of parties with open items, aged on {@code asOf}, and the totals of all of them. */
    public AgingPage aging(
            PartyType type, @Nullable LocalDate asOf, boolean overdueOnly, @Nullable UUID after, int fetch) {
        LocalDate day = asOf != null ? asOf : settings.today();
        List<UUID> page = parties.partiesWithOpenItems(type, overdueOnly, day, after, fetch);
        Map<UUID, List<OpenItem>> items = parties.openItems(type, page).stream()
                .collect(Collectors.groupingBy(i -> i.party().id()));
        List<AgingRow> rows = new ArrayList<>();
        for (UUID party : page) {
            List<OpenItem> open = items.getOrDefault(party, List.of());
            rows.add(new AgingRow(
                    party,
                    open.stream().map(OpenItem::remaining).reduce(BigDecimal.ZERO, BigDecimal::add),
                    Aging.of(open, day),
                    open.stream()
                            .filter(OpenItem::isDebit)
                            .map(OpenItem::documentDate)
                            .min(Comparator.naturalOrder())
                            .orElse(null),
                    open.size()));
        }
        return new AgingPage(day, type, rows, parties.totals(type, day, overdueOnly), settings.currency());
    }

    public record AgingRow(
            UUID partyId,
            BigDecimal balance,
            Aging.Buckets buckets,
            @Nullable LocalDate oldestOpenDate,
            int openItems) {}

    public record AgingPage(
            LocalDate asOf, PartyType type, List<AgingRow> rows, Aging.Buckets totals, String currency) {}

    /** A party's statement: opening balance, its documents in the range with a running balance, and what is open. */
    public Statement statement(PartyRef party, @Nullable LocalDate from, @Nullable LocalDate to) {
        if (!parties.isKnown(party)) {
            throw new NotFoundException(FinanceErrorCode.PARTY_NOT_KNOWN, Map.of("partyId", party.id()));
        }
        LocalDate end = to != null ? to : settings.today();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        BigDecimal opening = parties.amountBefore(party, start);
        BigDecimal running = opening;
        List<StatementEntry> entries = new ArrayList<>();
        for (OpenItem item : parties.itemsBetween(party, start, end)) {
            running = running.add(item.amount());
            boolean owedToUs = party.type() == PartyType.CUSTOMER;
            boolean increases = item.amount().signum() > 0;
            BigDecimal amount = item.amount().abs();
            entries.add(new StatementEntry(
                    item.documentDate(),
                    item.documentType(),
                    item.documentId(),
                    increases == owedToUs ? amount : BigDecimal.ZERO,
                    increases == owedToUs ? BigDecimal.ZERO : amount,
                    running));
        }
        List<OpenItem> open = parties.openItems(party.type(), List.of(party.id()));
        return new Statement(party, start, end, opening, entries, running, open, end, settings.currency());
    }

    public record StatementEntry(
            LocalDate date,
            DocumentType type,
            UUID documentId,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal balance) {}

    public record Statement(
            PartyRef party,
            LocalDate from,
            LocalDate to,
            BigDecimal opening,
            List<StatementEntry> entries,
            BigDecimal closing,
            List<OpenItem> open,
            LocalDate asOf,
            String currency) {}

    public List<FiscalPeriod> periods(@Nullable Period before, int fetch) {
        return periods.page(before, fetch);
    }

    public FiscalPeriod period(Period period) {
        return periods.find(period).orElse(FiscalPeriod.open(period));
    }

    public int fiscalYearStartMonth() {
        return settings.fiscalYearStartMonth();
    }

    public String currency() {
        return settings.currency();
    }
}
