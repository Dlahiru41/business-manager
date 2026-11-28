package com.bmp.finance.application;

import com.bmp.finance.api.LedgerQueries;
import com.bmp.finance.api.dto.AgingView;
import com.bmp.finance.api.dto.PosClearingDifference;
import com.bmp.finance.api.dto.ProfitAndLossView;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.port.LedgerReader;
import com.bmp.finance.domain.port.PartyAccounts;
import com.bmp.finance.domain.service.Aging;
import com.bmp.finance.domain.service.ProfitAndLoss;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link LedgerQueries} for reporting, over finance's own read models. */
@Component
class LedgerQueriesImpl implements LedgerQueries {

    private static final int PAGE = 500;

    private final FinanceQueries queries;
    private final LedgerReader ledger;
    private final PartyAccounts parties;
    private final FinanceSettings settings;

    LedgerQueriesImpl(FinanceQueries queries, LedgerReader ledger, PartyAccounts parties, FinanceSettings settings) {
        this.queries = queries;
        this.ledger = ledger;
        this.parties = parties;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public ProfitAndLossView profitAndLoss(YearMonth from, YearMonth to) {
        ProfitAndLoss pl =
                queries.profitAndLoss(new Period(from), new Period(to)).result();
        return new ProfitAndLossView(
                settings.currency(),
                pl.revenue(),
                pl.returns(),
                pl.discounts(),
                pl.netRevenue(),
                pl.costOfGoodsSold(),
                pl.wastage(),
                pl.countVariance(),
                pl.purchasePriceVariance(),
                pl.cashOverShort(),
                pl.operatingExpenses(),
                pl.profit());
    }

    @Override
    @Transactional(readOnly = true)
    public AgingView aging(String partyType, LocalDate asOf) {
        PartyType type = PartyType.valueOf(partyType);
        List<AgingView.Row> rows = new ArrayList<>();
        UUID after = null;
        while (true) {
            List<UUID> page = parties.partiesWithOpenItems(type, false, asOf, after, PAGE);
            Map<UUID, List<OpenItem>> items = parties.openItems(type, page).stream()
                    .collect(Collectors.groupingBy(i -> i.party().id()));
            for (UUID party : page) {
                List<OpenItem> open = items.getOrDefault(party, List.of());
                rows.add(new AgingView.Row(
                        party,
                        open.stream().map(OpenItem::remaining).reduce(BigDecimal.ZERO, BigDecimal::add),
                        buckets(Aging.of(open, asOf))));
            }
            if (page.size() < PAGE) {
                break;
            }
            after = page.getLast();
        }
        return new AgingView(settings.currency(), rows, buckets(parties.totals(type, asOf, false)));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal overdueReceivables(LocalDate asOf) {
        return parties.overdueCustomers(asOf).stream()
                .map(PartyAccounts.Overdue::overdueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PosClearingDifference> posClearingNotZero(LocalDate before) {
        return ledger.posClearingDifferencesBefore(before).stream()
                .map(d -> new PosClearingDifference(d.billId(), d.amount(), d.since()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastPostedAt() {
        return ledger.lastPostedAt();
    }

    private static AgingView.Buckets buckets(Aging.Buckets b) {
        return new AgingView.Buckets(b.days0To30(), b.days31To60(), b.days61To90(), b.days90Plus());
    }
}
