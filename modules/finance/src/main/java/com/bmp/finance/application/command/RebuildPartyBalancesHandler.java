package com.bmp.finance.application.command;

import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.port.OpenItemStore;
import com.bmp.finance.domain.port.OpenItemStore.Balance;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recomputes {@code party_balance} from the open items (06 §6); counts the rows that differed. */
@Service
public class RebuildPartyBalancesHandler {

    private static final Logger log = LoggerFactory.getLogger(RebuildPartyBalancesHandler.class);

    private final OpenItemStore store;

    public RebuildPartyBalancesHandler(OpenItemStore store) {
        this.store = store;
    }

    /** How many parties were recomputed and how many had a different stored balance. */
    public record Result(int parties, int changed) {}

    @Transactional
    public Result rebuild() {
        Map<PartyRef, Balance> stored = new HashMap<>();
        store.stored().forEach(b -> stored.put(b.party(), b));
        List<Balance> recomputed = store.recomputed();
        int changed = 0;
        for (Balance b : recomputed) {
            store.lockParty(b.party());
            Balance before = stored.get(b.party());
            boolean same = before != null
                    && before.balance().compareTo(b.balance()) == 0
                    && Objects.equals(before.oldestOpenDate(), b.oldestOpenDate());
            if (!same) {
                changed++;
                store.balance(b.party(), b.balance(), b.oldestOpenDate());
            }
        }
        for (Balance b : stored.values()) {
            boolean vanished = recomputed.stream().noneMatch(r -> r.party().equals(b.party()));
            if (vanished && (b.balance().signum() != 0 || b.oldestOpenDate() != null)) {
                changed++;
                store.balance(b.party(), BigDecimal.ZERO, null);
            }
        }
        log.atInfo()
                .addKeyValue("parties", recomputed.size())
                .addKeyValue("changed", changed)
                .log("Party balances rebuilt");
        return new Result(recomputed.size(), changed);
    }
}
