package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.model.Account;
import com.bmp.finance.adapter.in.web.generated.model.AccountStatus;
import com.bmp.finance.adapter.in.web.generated.model.AccountType;
import com.bmp.finance.adapter.in.web.generated.model.Aging;
import com.bmp.finance.adapter.in.web.generated.model.AgingBuckets;
import com.bmp.finance.adapter.in.web.generated.model.AgingRow;
import com.bmp.finance.adapter.in.web.generated.model.AgingTotals;
import com.bmp.finance.adapter.in.web.generated.model.Allocation;
import com.bmp.finance.adapter.in.web.generated.model.CashDrop;
import com.bmp.finance.adapter.in.web.generated.model.CloseChecks;
import com.bmp.finance.adapter.in.web.generated.model.CloseChecksPosClearingDifferencesInner;
import com.bmp.finance.adapter.in.web.generated.model.CloseChecksUnreconciledDrawersInner;
import com.bmp.finance.adapter.in.web.generated.model.ClosePeriod202Response;
import com.bmp.finance.adapter.in.web.generated.model.Dimensions;
import com.bmp.finance.adapter.in.web.generated.model.DocumentType;
import com.bmp.finance.adapter.in.web.generated.model.Expense;
import com.bmp.finance.adapter.in.web.generated.model.ExpenseCategory;
import com.bmp.finance.adapter.in.web.generated.model.FiscalPeriod;
import com.bmp.finance.adapter.in.web.generated.model.JournalEntry;
import com.bmp.finance.adapter.in.web.generated.model.JournalLine;
import com.bmp.finance.adapter.in.web.generated.model.JournalLineDebit;
import com.bmp.finance.adapter.in.web.generated.model.ListAccounts200ResponsePage;
import com.bmp.finance.adapter.in.web.generated.model.ListAccounts400Response;
import com.bmp.finance.adapter.in.web.generated.model.OpenItem;
import com.bmp.finance.adapter.in.web.generated.model.PaidFrom;
import com.bmp.finance.adapter.in.web.generated.model.PartyType;
import com.bmp.finance.adapter.in.web.generated.model.PeriodStatus;
import com.bmp.finance.adapter.in.web.generated.model.ProfitAndLoss;
import com.bmp.finance.adapter.in.web.generated.model.ProfitAndLossAccount;
import com.bmp.finance.adapter.in.web.generated.model.SourceType;
import com.bmp.finance.adapter.in.web.generated.model.Statement;
import com.bmp.finance.adapter.in.web.generated.model.StatementEntry;
import com.bmp.finance.adapter.in.web.generated.model.SupplierPaidFrom;
import com.bmp.finance.adapter.in.web.generated.model.SupplierPayment;
import com.bmp.finance.adapter.in.web.generated.model.SystemKey;
import com.bmp.finance.adapter.in.web.generated.model.TrialBalance;
import com.bmp.finance.adapter.in.web.generated.model.TrialBalanceRow;
import com.bmp.finance.application.query.CloseChecks.Result;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.port.LedgerReader;
import com.bmp.finance.domain.service.Aging.Buckets;
import com.bmp.persistence.operation.Operations;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Maps finance read models to the generated API models; amounts as decimal strings, 2 to 4 decimals. */
@Component
class FinanceWebMapper {

    private final JsonMapper json;

    FinanceWebMapper(JsonMapper json) {
        this.json = json;
    }

    static JournalLineDebit money(BigDecimal amount, String currency) {
        BigDecimal plain = amount.stripTrailingZeros();
        BigDecimal shown = plain.scale() < 2 ? amount.setScale(2, java.math.RoundingMode.UNNECESSARY) : plain;
        return new JournalLineDebit().amount(shown.toPlainString()).currency(currency);
    }

    static @Nullable OffsetDateTime time(@Nullable Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }

    static OffsetDateTime at(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    static ListAccounts200ResponsePage page(PageResponse.Page page) {
        return new ListAccounts200ResponsePage()
                .limit(page.limit())
                .nextCursor(page.nextCursor())
                .hasMore(page.hasMore());
    }

    Account account(com.bmp.finance.domain.model.Account a) {
        return new Account()
                .id(a.id())
                .code(a.code())
                .name(a.name())
                .type(AccountType.valueOf(a.type().name()))
                .subtype(a.subtype())
                .parentId(a.parentId())
                .systemKey(
                        a.systemKey() == null
                                ? null
                                : SystemKey.valueOf(a.systemKey().name()))
                .status(AccountStatus.valueOf(a.status().name()))
                .version(a.version());
    }

    JournalEntry journal(LedgerReader.JournalView j) {
        BigDecimal debit =
                j.lines().stream().map(LedgerReader.LineView::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit =
                j.lines().stream().map(LedgerReader.LineView::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new JournalEntry()
                .id(j.id())
                .number(j.number())
                .entryDate(j.entryDate())
                .period(j.period().toString())
                .sourceType(SourceType.valueOf(j.sourceType().name()))
                .sourceId(j.sourceId())
                .sourceEventId(j.sourceEventId())
                .memo(j.memo())
                .reversesId(j.reversesId())
                .reversedById(j.reversedById())
                .postedAt(at(j.postedAt()))
                .postedBy(j.postedBy())
                .lines(j.lines().stream().map(l -> line(l, j.currency())).toList())
                .totalDebit(money(debit, j.currency()))
                .totalCredit(money(credit, j.currency()));
    }

    private static JournalLine line(LedgerReader.LineView l, String currency) {
        PartyRef party = l.party();
        var d = l.dimensions();
        return new JournalLine()
                .id(l.id())
                .accountId(l.accountId())
                .accountCode(l.accountCode())
                .systemKey(
                        l.systemKey() == null
                                ? null
                                : SystemKey.valueOf(l.systemKey().name()))
                .debit(money(l.debit(), currency))
                .credit(money(l.credit(), currency))
                .partyType(party == null ? null : PartyType.valueOf(party.type().name()))
                .partyId(party == null ? null : party.id())
                .dimensions(
                        d.isEmpty()
                                ? null
                                : new Dimensions()
                                        .variantId(d.variantId())
                                        .productId(d.productId())
                                        .channel(d.channel())
                                        .locationId(d.locationId())
                                        .billId(d.billId()));
    }

    TrialBalance trialBalance(FinanceQueries.TrialBalance t) {
        return new TrialBalance()
                .asOf(t.asOf())
                .period(t.period() == null ? null : t.period().toString())
                .rows(t.rows().stream()
                        .map(r -> new TrialBalanceRow()
                                .accountId(r.accountId())
                                .code(r.code())
                                .name(r.name())
                                .type(AccountType.valueOf(r.type().name()))
                                .systemKey(
                                        r.systemKey() == null
                                                ? null
                                                : SystemKey.valueOf(
                                                        r.systemKey().name()))
                                .debit(money(r.debit(), t.currency()))
                                .credit(money(r.credit(), t.currency()))
                                .balance(money(r.balance(), t.currency())))
                        .toList())
                .totalDebit(money(t.totalDebit(), t.currency()))
                .totalCredit(money(t.totalCredit(), t.currency()));
    }

    ProfitAndLoss profitAndLoss(FinanceQueries.ProfitAndLossReport report) {
        var pl = report.result();
        String c = report.currency();
        return new ProfitAndLoss()
                .from(report.from().toString())
                .to(report.to().toString())
                .revenue(money(pl.revenue(), c))
                .returns(money(pl.returns(), c))
                .discounts(money(pl.discounts(), c))
                .netRevenue(money(pl.netRevenue(), c))
                .costOfGoodsSold(money(pl.costOfGoodsSold(), c))
                .wastage(money(pl.wastage(), c))
                .countVariance(money(pl.countVariance(), c))
                .purchasePriceVariance(money(pl.purchasePriceVariance(), c))
                .cashOverShort(money(pl.cashOverShort(), c))
                .operatingExpenses(money(pl.operatingExpenses(), c))
                .profit(money(pl.profit(), c))
                .accounts(report.accounts().stream()
                        .map(a -> new ProfitAndLossAccount()
                                .accountId(a.accountId())
                                .code(a.code())
                                .name(a.name())
                                .type(AccountType.valueOf(a.type().name()))
                                .systemKey(
                                        a.systemKey() == null
                                                ? null
                                                : SystemKey.valueOf(
                                                        a.systemKey().name()))
                                .amount(money(
                                        a.type().debitNormal()
                                                ? a.balance()
                                                : a.balance().negate(),
                                        c)))
                        .toList());
    }

    ExpenseCategory category(com.bmp.finance.domain.model.ExpenseCategory c) {
        return new ExpenseCategory()
                .id(c.id())
                .code(c.code())
                .name(c.name())
                .accountId(c.accountId())
                .status(AccountStatus.valueOf(c.status().name()))
                .version(c.version());
    }

    Expense expense(com.bmp.finance.domain.model.Expense e) {
        return new Expense()
                .id(e.id())
                .categoryId(e.categoryId())
                .expenseDate(e.expenseDate())
                .amount(money(e.amount(), e.currency()))
                .paidFrom(PaidFrom.valueOf(e.paidFrom().name()))
                .drawerSessionId(e.drawerSessionId())
                .supplierId(e.supplierId())
                .dueDate(e.dueDate())
                .description(e.description())
                .attachmentKeys(e.attachmentKeys())
                .journalId(e.journalId())
                .createdAt(at(e.createdAt()));
    }

    SupplierPayment supplierPayment(com.bmp.finance.domain.model.SupplierPayment p) {
        return new SupplierPayment()
                .id(p.id())
                .supplierId(p.supplierId())
                .amount(money(p.amount(), p.currency()))
                .paidFrom(p.fromDrawer() ? SupplierPaidFrom.CASH_DRAWER : SupplierPaidFrom.BANK)
                .drawerSessionId(p.drawerSessionId())
                .reference(p.reference())
                .paidAt(at(p.paidAt()))
                .allocations(p.allocations().stream()
                        .map(a -> new Allocation().documentId(a.documentId()).amount(money(a.amount(), p.currency())))
                        .toList())
                .journalId(p.journalId());
    }

    CashDrop cashDrop(com.bmp.finance.domain.model.CashDrop d) {
        return new CashDrop()
                .id(d.id())
                .drawerSessionId(d.drawerSessionId())
                .registerId(d.registerId())
                .amount(money(d.amount(), d.currency()))
                .reference(d.reference())
                .droppedAt(at(d.droppedAt()))
                .journalId(d.journalId());
    }

    Aging aging(FinanceQueries.AgingPage a, PageResponse.Page page, java.util.List<FinanceQueries.AgingRow> rows) {
        String c = a.currency();
        return new Aging()
                .asOf(a.asOf())
                .partyType(PartyType.valueOf(a.type().name()))
                .rows(rows.stream()
                        .map(r -> new AgingRow()
                                .partyId(r.partyId())
                                .balance(money(r.balance(), c))
                                .buckets(buckets(r.buckets(), c))
                                .oldestOpenDate(r.oldestOpenDate())
                                .openItems(r.openItems()))
                        .toList())
                .totals(new AgingTotals().balance(money(a.totals().total(), c)).buckets(buckets(a.totals(), c)))
                .page(page(page));
    }

    private static AgingBuckets buckets(Buckets b, String c) {
        return new AgingBuckets()
                .days0To30(money(b.days0To30(), c))
                .days31To60(money(b.days31To60(), c))
                .days61To90(money(b.days61To90(), c))
                .days90Plus(money(b.days90Plus(), c));
    }

    Statement statement(FinanceQueries.Statement s) {
        String c = s.currency();
        return new Statement()
                .partyId(s.party().id())
                .partyType(PartyType.valueOf(s.party().type().name()))
                .from(s.from())
                .to(s.to())
                .openingBalance(money(s.opening(), c))
                .entries(s.entries().stream()
                        .map(e -> new StatementEntry()
                                .date(e.date())
                                .documentType(DocumentType.valueOf(e.type().name()))
                                .documentId(e.documentId())
                                .debit(money(e.debit(), c))
                                .credit(money(e.credit(), c))
                                .balance(money(e.balance(), c)))
                        .toList())
                .closingBalance(money(s.closing(), c))
                .openItems(s.open().stream().map(i -> openItem(i, s.asOf(), c)).toList());
    }

    private static OpenItem openItem(com.bmp.finance.domain.model.OpenItem i, LocalDate asOf, String c) {
        return new OpenItem()
                .id(i.id())
                .documentType(DocumentType.valueOf(i.documentType().name()))
                .documentId(i.documentId())
                .documentDate(i.documentDate())
                .dueDate(i.dueDate())
                .amount(money(i.amount(), c))
                .settled(money(i.settled(), c))
                .open(money(i.remaining(), c))
                .daysOverdue((int) Math.max(0, ChronoUnit.DAYS.between(i.dueDate(), asOf)));
    }

    FiscalPeriod period(com.bmp.finance.domain.model.FiscalPeriod p, int fiscalYearStartMonth) {
        return new FiscalPeriod()
                .period(p.period().toString())
                .status(PeriodStatus.valueOf(p.status().name()))
                .closedAt(time(p.closedAt()))
                .closedBy(p.closedBy())
                .yearEnd(p.period().isYearEnd(fiscalYearStartMonth));
    }

    CloseChecks closeChecks(Period period, Result r, String currency) {
        return new CloseChecks()
                .period(period.toString())
                .failedEvents((int) r.failedEvents())
                .posClearingDifferences(r.differences().stream()
                        .map(d -> new CloseChecksPosClearingDifferencesInner()
                                .billId(d.billId())
                                .amount(money(d.amount(), currency)))
                        .toList())
                .unreconciledDrawers(r.unreconciled().stream()
                        .map(s -> new CloseChecksUnreconciledDrawersInner()
                                .sessionId(s.id())
                                .registerId(s.registerId())
                                .openedAt(at(s.openedAt())))
                        .toList())
                .ready(r.ready());
    }

    ClosePeriod202Response operation(Operations.Operation o) {
        return new ClosePeriod202Response()
                .id(o.id())
                .type(o.type())
                .status(ClosePeriod202Response.StatusEnum.fromValue(o.status()))
                .progress(o.progress())
                .resultUrl(o.resultRef())
                .problem(o.problem() == null ? null : json.readValue(o.problem(), ListAccounts400Response.class))
                .createdAt(at(o.createdAt()))
                .updatedAt(at(o.updatedAt()));
    }
}
