package com.bmp.finance.application.command;

import com.bmp.finance.application.Drawers;
import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.domain.model.CashDrop;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.port.DocumentLedger;
import com.bmp.finance.domain.posting.CashDropRule;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cash taken from a drawer to the bank: a DROP on the drawer, Dr BANK / Cr CASH_DRAWER. */
@Service
public class CashDropHandler {

    private static final Logger log = LoggerFactory.getLogger(CashDropHandler.class);
    private static final CashDropRule RULE = new CashDropRule();

    private final DocumentLedger documents;
    private final JournalPoster poster;
    private final Drawers drawers;
    private final FinanceSettings settings;
    private final Clock clock;

    public CashDropHandler(
            DocumentLedger documents, JournalPoster poster, Drawers drawers, FinanceSettings settings, Clock clock) {
        this.documents = documents;
        this.poster = poster;
        this.drawers = drawers;
        this.settings = settings;
        this.clock = clock;
    }

    public record Drop(
            UUID id,
            UUID drawerSessionId,
            BigDecimal amount,
            @Nullable String reference,
            @Nullable Instant at) {}

    @Transactional
    public CashDrop drop(Drop d) {
        Optional<CashDrop> known = documents.cashDrop(d.id());
        if (known.isPresent()) {
            return known.get();
        }
        Instant at = d.at() != null ? d.at() : clock.instant();
        DrawerSessionInfo session =
                drawers.move(d.drawerSessionId(), d.id(), "DROP", d.amount(), "Cash drop to the bank", at);
        PostedJournal journal = poster.post(
                        () -> RULE.post(new CashDropRule.Drop(d.id(), d.amount(), settings.businessDate(at))))
                .orElseThrow();
        CashDrop drop = new CashDrop(
                d.id(),
                session.id(),
                session.registerId(),
                d.amount(),
                settings.currency(),
                d.reference(),
                at,
                journal.id());
        documents.append(drop);
        log.atInfo().addKeyValue("cashDropId", drop.id()).log("Cash dropped to the bank");
        return drop;
    }
}
