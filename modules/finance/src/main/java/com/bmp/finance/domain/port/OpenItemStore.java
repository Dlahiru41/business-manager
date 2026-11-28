package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.Settlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Open items, their settlements and the {@code party_balance} projection (FIN-07). */
public interface OpenItemStore {

    /** Locks the party's balance row (creating it), so its items change one transaction at a time. */
    void lockParty(PartyRef party);

    List<OpenItem> openItems(PartyRef party);

    Optional<OpenItem> findByDocument(PartyRef party, DocumentType type, UUID documentId);

    void insert(OpenItem item, Instant at);

    void update(OpenItem item, Instant at);

    void settled(List<Settlement> settlements, Instant at, java.util.function.Supplier<UUID> ids);

    void balance(PartyRef party, BigDecimal balance, @Nullable LocalDate oldestOpenDate);

    /** What the projection holds for a party. */
    record Balance(
            PartyRef party, BigDecimal balance, @Nullable LocalDate oldestOpenDate) {}

    /** Every balance row and every party with open items, recomputed from the items. */
    List<Balance> recomputed();

    List<Balance> stored();
}
