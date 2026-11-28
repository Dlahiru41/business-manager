package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.JournalLine;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.port.AccountRepository;
import com.bmp.finance.domain.port.JournalLedger;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.ValidationFailure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rule FIN-04: journals entered by hand, with a memo, and reversals of any journal by a new one. */
@Service
public class JournalHandlers {

    private static final Logger log = LoggerFactory.getLogger(JournalHandlers.class);

    private final JournalPoster poster;
    private final JournalLedger ledger;
    private final AccountRepository accounts;
    private final FinanceSettings settings;

    public JournalHandlers(
            JournalPoster poster, JournalLedger ledger, AccountRepository accounts, FinanceSettings settings) {
        this.poster = poster;
        this.ledger = ledger;
        this.accounts = accounts;
        this.settings = settings;
    }

    /** A manual line: exactly one of debit and credit, above zero. */
    public record ManualLine(
            UUID accountId,
            @Nullable BigDecimal debit,
            @Nullable BigDecimal credit,
            @Nullable PartyRef party,
            Dimensions dimensions) {}

    public record ManualJournal(UUID id, LocalDate entryDate, String memo, List<ManualLine> lines) {

        public ManualJournal {
            lines = List.copyOf(lines);
        }
    }

    public record ReverseJournal(
            UUID id, UUID journalId, String memo, @Nullable LocalDate entryDate) {}

    @Transactional
    public PostedJournal manual(ManualJournal m) {
        Optional<PostedJournal> known = ledger.find(m.id());
        if (known.isPresent()) {
            return known.get();
        }
        List<FieldError> errors = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (int i = 0; i < m.lines().size(); i++) {
            ManualLine line = m.lines().get(i);
            BigDecimal d = line.debit() == null ? BigDecimal.ZERO : line.debit();
            BigDecimal c = line.credit() == null ? BigDecimal.ZERO : line.credit();
            if ((d.signum() > 0) == (c.signum() > 0) || d.signum() < 0 || c.signum() < 0) {
                errors.add(new FieldError("lines[" + i + "]", "ONE_SIDE", "A line has a debit or a credit above zero"));
            }
            debit = debit.add(d);
            credit = credit.add(c);
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailure(errors);
        }
        Map<UUID, Account> found = accounts
                .findAllById(m.lines().stream().map(ManualLine::accountId).toList())
                .stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        for (ManualLine line : m.lines()) {
            Account account = found.get(line.accountId());
            if (account == null) {
                throw new BusinessRuleViolation(
                        FinanceErrorCode.UNKNOWN_ACCOUNT, Map.of("accountId", line.accountId()));
            }
            if (!account.isActive()) {
                throw new BusinessRuleViolation(
                        FinanceErrorCode.ACCOUNT_INACTIVE, Map.of("accountId", line.accountId()));
            }
        }
        if (debit.compareTo(credit) != 0) {
            throw new BusinessRuleViolation(
                    FinanceErrorCode.JOURNAL_UNBALANCED,
                    Map.of("debit", debit.toPlainString(), "credit", credit.toPlainString()));
        }
        JournalEntry.Builder builder = JournalEntry.builder(SourceType.MANUAL, m.id(), null, m.entryDate())
                .memo(m.memo());
        for (ManualLine line : m.lines()) {
            builder.line(new JournalLine(
                    AccountRef.of(line.accountId()),
                    scaled(line.debit()),
                    scaled(line.credit()),
                    line.party(),
                    line.dimensions()));
        }
        JournalEntry entry = builder.build();
        PostedJournal posted = poster.post(m.id(), () -> Optional.of(entry)).orElseThrow();
        log.atInfo().addKeyValue("journalId", posted.id()).log("Manual journal posted");
        return posted;
    }

    @Transactional
    public PostedJournal reverse(ReverseJournal r) {
        Optional<PostedJournal> known = ledger.find(r.id());
        if (known.isPresent()) {
            return known.get();
        }
        PostedJournal original = ledger.find(r.journalId())
                .orElseThrow(() ->
                        new NotFoundException(FinanceErrorCode.JOURNAL_NOT_FOUND, Map.of("journalId", r.journalId())));
        if (ledger.isReversed(original.id())) {
            throw new BusinessRuleViolation(
                    FinanceErrorCode.JOURNAL_ALREADY_REVERSED, Map.of("journalId", original.id()));
        }
        LocalDate date = r.entryDate() != null ? r.entryDate() : settings.today();
        JournalEntry reversal = original.reversal(SourceType.REVERSAL, original.id(), null, date, r.memo());
        return poster.post(r.id(), () -> Optional.of(reversal)).orElseThrow();
    }

    private static BigDecimal scaled(@Nullable BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(JournalEntry.SCALE, java.math.RoundingMode.HALF_UP);
    }
}
