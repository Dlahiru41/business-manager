package com.bmp.finance.application;

import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.Settlement;
import com.bmp.finance.domain.port.OpenItemStore;
import com.bmp.finance.domain.service.OpenItemSettler;
import com.bmp.shared.id.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule FIN-07: opens an item on a party's account and settles it against the party's open items, under the party's
 * lock, keeping {@code party_balance} in the same transaction.
 */
@Component
public class OpenItems {

    private final OpenItemStore store;
    private final IdGenerator ids;
    private final Clock clock;

    public OpenItems(OpenItemStore store, IdGenerator ids, Clock clock) {
        this.store = store;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Opens {@code amount} (signed) for the document, unless the document already has its item (redelivery).
     *
     * @param allocations documents to settle first
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OpenItem> open(
            PartyRef party,
            DocumentType type,
            UUID documentId,
            BigDecimal amount,
            LocalDate documentDate,
            LocalDate dueDate,
            List<OpenItemSettler.Allocation> allocations) {
        if (amount.signum() == 0) {
            return Optional.empty();
        }
        store.lockParty(party);
        if (store.findByDocument(party, type, documentId).isPresent()) {
            return Optional.empty();
        }
        List<OpenItem> open = store.openItems(party);
        OpenItem item = OpenItem.open(ids.newUuid(), party, type, documentId, amount, documentDate, dueDate);
        List<Settlement> settlements = OpenItemSettler.settle(item, open, allocations);
        Instant now = clock.instant();
        store.insert(item, now);
        Set<UUID> touched = new HashSet<>();
        settlements.forEach(s -> touched.add(s.againstItemId()));
        for (OpenItem target : open) {
            if (touched.contains(target.id())) {
                store.update(target, now);
            }
        }
        store.settled(settlements, now, ids::newUuid);
        List<OpenItem> all = new ArrayList<>(open);
        all.add(item);
        store.balance(party, balance(all), oldestOpen(all));
        return Optional.of(item);
    }

    /** The party's open items, as they are now (for checks before a payment). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OpenItem> openItemsOf(PartyRef party) {
        store.lockParty(party);
        return store.openItems(party);
    }

    static BigDecimal balance(List<OpenItem> items) {
        return items.stream().map(OpenItem::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static @org.jspecify.annotations.Nullable LocalDate oldestOpen(List<OpenItem> items) {
        return items.stream()
                .filter(OpenItem::isOpen)
                .filter(OpenItem::isDebit)
                .map(OpenItem::documentDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }
}
