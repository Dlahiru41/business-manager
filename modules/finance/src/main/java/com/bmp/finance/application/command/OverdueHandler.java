package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceEvents;
import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.port.PartyAccounts;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule FIN-08: customers with items more than {@code finance.overdueGraceDays} past due are reported with
 * {@code ReceivableOverdue}, at most once every seven days each.
 */
@Service
public class OverdueHandler {

    static final int DAYS_BETWEEN_NOTICES = 7;

    private static final Logger log = LoggerFactory.getLogger(OverdueHandler.class);

    private final PartyAccounts accounts;
    private final FinanceEvents events;
    private final FinanceSettings settings;

    public OverdueHandler(PartyAccounts accounts, FinanceEvents events, FinanceSettings settings) {
        this.accounts = accounts;
        this.events = events;
        this.settings = settings;
    }

    /** Reports the current tenant's overdue customers; returns how many were reported. */
    @Transactional
    public int report() {
        LocalDate asOf = settings.today();
        int reported = 0;
        for (PartyAccounts.Overdue o : accounts.overdueCustomers(asOf.minusDays(settings.overdueGraceDays()))) {
            Optional<LocalDate> last = accounts.lastNotified(o.customerId());
            if (last.isPresent() && last.get().plusDays(DAYS_BETWEEN_NOTICES).isAfter(asOf)) {
                continue;
            }
            events.receivableOverdue(
                    o.customerId(),
                    accounts.balance(PartyRef.customer(o.customerId())).orElse(o.overdueAmount()),
                    o.overdueAmount(),
                    (int) ChronoUnit.DAYS.between(o.oldestDue(), asOf),
                    settings.currency(),
                    asOf);
            accounts.notified(o.customerId(), asOf);
            reported++;
        }
        if (reported > 0) {
            log.atInfo().addKeyValue("customers", reported).log("Overdue receivables reported");
        }
        return reported;
    }
}
