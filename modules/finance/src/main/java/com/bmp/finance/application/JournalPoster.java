package com.bmp.finance.application;

import com.bmp.finance.api.events.JournalPosted;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.FiscalPeriod;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.JournalLine;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.finance.domain.port.AccountRepository;
import com.bmp.finance.domain.port.JournalLedger;
import com.bmp.finance.domain.port.PeriodStore;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.id.IdGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts journals (FIN-02..FIN-05): runs the rule, counts imbalances (FIN-03), finds the period that takes the posting
 * (late postings to the first open one), resolves the accounts, numbers the journal, writes it and publishes
 * {@code JournalPosted}.
 */
@Component
public class JournalPoster {

    static final String NUMBER_SCOPE = "JE";

    private static final Logger log = LoggerFactory.getLogger(JournalPoster.class);

    private final AccountRepository accounts;
    private final PeriodStore periods;
    private final JournalLedger ledger;
    private final NumberSequences numbers;
    private final FinanceEvents events;
    private final FinanceSettings settings;
    private final IdGenerator ids;
    private final Clock clock;
    private final Counter imbalance;

    public JournalPoster(
            AccountRepository accounts,
            PeriodStore periods,
            JournalLedger ledger,
            NumberSequences financeNumbers,
            FinanceEvents events,
            FinanceSettings settings,
            IdGenerator ids,
            Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        this.accounts = accounts;
        this.periods = periods;
        this.ledger = ledger;
        this.numbers = financeNumbers;
        this.events = events;
        this.settings = settings;
        this.ids = ids;
        this.clock = clock;
        this.imbalance = Counter.builder("bmp.ledger.imbalance")
                .description("Journals refused because debits and credits differ (FIN-03); must stay zero")
                .register(meters.getIfUnique(SimpleMeterRegistry::new));
    }

    /** Posts what the rule returns; nothing when it has nothing to post. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostedJournal> post(Supplier<Optional<JournalEntry>> rule) {
        Optional<JournalEntry> entry;
        try {
            entry = rule.get();
        } catch (TechnicalException e) {
            if (e.errorCode() == FinanceErrorCode.LEDGER_IMBALANCE) {
                imbalance.increment();
            }
            throw e;
        }
        return entry.map(this::write);
    }

    private PostedJournal write(JournalEntry entry) {
        Period own = Period.of(entry.entryDate());
        Period period = own;
        FiscalPeriod status = periods.lockForPosting(period);
        while (!status.acceptsPostings()) {
            period = period.next();
            status = periods.lockForPosting(period);
        }
        JournalEntry posted = period.equals(own) ? entry : entry.latePosting(period.firstDay());
        Map<AccountRef, Account> resolved = resolve(posted);
        List<UUID> accountIds = new ArrayList<>();
        List<JournalPosted.Line> eventLines = new ArrayList<>();
        List<PostedJournal.Line> lines = new ArrayList<>();
        for (JournalLine line : posted.lines()) {
            Account account = java.util.Objects.requireNonNull(resolved.get(line.account()), "resolved");
            accountIds.add(account.id());
            lines.add(
                    new PostedJournal.Line(account.id(), line.debit(), line.credit(), line.party(), line.dimensions()));
            eventLines.add(new JournalPosted.Line(
                    account.id(),
                    account.code(),
                    account.systemKey() == null ? null : account.systemKey().name(),
                    line.debit(),
                    line.credit(),
                    line.party() == null ? null : line.party().type().name(),
                    line.party() == null ? null : line.party().id()));
        }
        UUID id = ids.newUuid();
        String number = "%s-%06d".formatted(NUMBER_SCOPE, numbers.next(NUMBER_SCOPE));
        Instant now = clock.instant();
        String currency = settings.currency();
        var user = ActorContext.currentOrSystem().userId();
        ledger.append(new JournalLedger.NewJournal(
                id, number, period, posted, accountIds, currency, now, user == null ? null : user.value()));
        events.journalPosted(new FinanceEvents.JournalPostedFacts(
                id,
                number,
                posted.entryDate(),
                period.toString(),
                posted.sourceType().name(),
                posted.sourceId(),
                posted.sourceEventId(),
                posted.reversesId(),
                posted.memo(),
                currency,
                eventLines,
                now));
        log.atInfo()
                .addKeyValue("journalId", id)
                .addKeyValue("number", number)
                .addKeyValue("sourceType", posted.sourceType())
                .addKeyValue("period", period)
                .log("Journal posted");
        return new PostedJournal(
                id,
                number,
                posted.entryDate(),
                period,
                posted.sourceType(),
                posted.sourceId(),
                posted.reversesId(),
                lines);
    }

    /** Every account of the entry; a seeded account not seeded yet makes an event wait (TenantCreated first). */
    private Map<AccountRef, Account> resolve(JournalEntry entry) {
        List<SystemKey> keys = entry.lines().stream()
                .map(JournalLine::account)
                .filter(AccountRef.Seeded.class::isInstance)
                .map(ref -> ((AccountRef.Seeded) ref).key())
                .distinct()
                .toList();
        List<UUID> byId = entry.lines().stream()
                .map(JournalLine::account)
                .filter(AccountRef.Id.class::isInstance)
                .map(ref -> ((AccountRef.Id) ref).accountId())
                .distinct()
                .toList();
        Map<AccountRef, Account> resolved = new java.util.HashMap<>();
        if (!keys.isEmpty()) {
            Map<SystemKey, Account> seeded = accounts.findBySystemKeys(keys).stream()
                    .collect(Collectors.toMap(
                            a -> java.util.Objects.requireNonNull(a.systemKey()), Function.identity()));
            for (SystemKey key : keys) {
                Account account = seeded.get(key);
                if (account == null) {
                    throw missing(entry, "account " + key);
                }
                resolved.put(AccountRef.of(key), account);
            }
        }
        if (!byId.isEmpty()) {
            Map<UUID, Account> found =
                    accounts.findAllById(byId).stream().collect(Collectors.toMap(Account::id, Function.identity()));
            for (UUID accountId : byId) {
                Account account = found.get(accountId);
                if (account == null) {
                    throw missing(entry, "account " + accountId);
                }
                resolved.put(AccountRef.of(accountId), account);
            }
        }
        return resolved;
    }

    private static RuntimeException missing(JournalEntry entry, String what) {
        UUID event = entry.sourceEventId();
        if (event != null) {
            return new OutOfOrderEventException(event, what);
        }
        return new TechnicalException(FinanceErrorCode.POSTING_RULE_MISSING, Map.of("missing", what), null);
    }
}
