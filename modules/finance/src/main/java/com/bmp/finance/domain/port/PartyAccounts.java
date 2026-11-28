package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.service.Aging;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the parties' accounts: aging, statements, exposure and the overdue notices (FIN-07, FIN-08). */
public interface PartyAccounts {

    /** Parties of the type with open items (past due on {@code asOf} when {@code overdueOnly}), by id after a cursor. */
    List<UUID> partiesWithOpenItems(
            PartyType type, boolean overdueOnly, LocalDate asOf, @Nullable UUID after, int limit);

    List<OpenItem> openItems(PartyType type, Collection<UUID> parties);

    /** The aging buckets of every open item of the type. */
    Aging.Buckets totals(PartyType type, LocalDate asOf, boolean overdueOnly);

    /** Σ amounts of the party's items dated before {@code date}: the opening balance of a statement. */
    BigDecimal amountBefore(PartyRef party, LocalDate date);

    /** The party's items dated in {@code [from, to]}, by date then id. */
    List<OpenItem> itemsBetween(PartyRef party, LocalDate from, LocalDate to);

    boolean isKnown(PartyRef party);

    /** The {@code party_balance} projection of a party. */
    Optional<BigDecimal> balance(PartyRef party);

    /** A customer with items past {@code dueBefore}: what is overdue and since when. */
    record Overdue(UUID customerId, BigDecimal overdueAmount, LocalDate oldestDue) {}

    List<Overdue> overdueCustomers(LocalDate dueBefore);

    Optional<LocalDate> lastNotified(UUID customerId);

    void notified(UUID customerId, LocalDate on);
}
