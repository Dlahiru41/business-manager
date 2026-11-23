package com.bmp.inventory.application.command;

import com.bmp.inventory.application.InventorySettings;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.CostLayer;
import com.bmp.inventory.domain.policy.CostingPolicy;
import com.bmp.inventory.domain.policy.FifoCostingMarker;
import com.bmp.inventory.domain.port.BalanceStore;
import com.bmp.inventory.domain.port.CostLayerStore;
import com.bmp.inventory.domain.port.LedgerReader;
import com.bmp.inventory.domain.service.BalanceReplay;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule INV-16: recomputes every balance of the tenant from the ledger under the balances' row locks, so postings wait
 * and nothing is lost, and rewrites those that differ. The ledger itself never changes.
 */
@Service
public class RebuildBalancesHandler {

    private static final Logger log = LoggerFactory.getLogger(RebuildBalancesHandler.class);

    private final LedgerReader ledger;
    private final BalanceStore balances;
    private final CostLayerStore layers;
    private final InventorySettings settings;
    private final Clock clock;

    public RebuildBalancesHandler(
            LedgerReader ledger,
            BalanceStore balances,
            CostLayerStore layers,
            InventorySettings settings,
            Clock clock) {
        this.ledger = ledger;
        this.balances = balances;
        this.layers = layers;
        this.settings = settings;
        this.clock = clock;
    }

    /** How many balances were checked and how many were rewritten. */
    public record Result(int balances, int differences, Instant rebuiltAt) {}

    @Transactional
    @Observed(name = "bmp.inventory.rebuild-balances")
    public Result rebuild() {
        CostingPolicy costing = settings.costing();
        Set<BalanceKey> keys = new TreeSet<>(ledger.balanceKeys());
        Map<BalanceKey, BalanceState> current = new HashMap<>(balances.lock(keys));
        // Read after locking: what was posted before the locks is in, nothing can be posted on them meanwhile.
        Map<BalanceKey, List<BalanceReplay.Recorded>> movements = ledger.movementsByBalance();
        Set<BalanceKey> late = new TreeSet<>(movements.keySet());
        late.removeAll(keys);
        if (!late.isEmpty()) {
            current.putAll(balances.lock(late));
            keys.addAll(late);
            movements = ledger.movementsByBalance();
        }
        boolean fifo = costing instanceof FifoCostingMarker;
        Map<BalanceKey, List<CostLayer>> currentLayers = fifo ? layers.load(keys) : Map.of();
        Map<BalanceKey, BalanceState> rebuilt = new HashMap<>();
        int differences = 0;
        for (BalanceKey key : keys) {
            BalanceState replayed = BalanceReplay.rebuild(movements.getOrDefault(key, List.of()), costing);
            BalanceState now = current.getOrDefault(key, BalanceState.empty());
            List<CostLayer> nowLayers = currentLayers.getOrDefault(key, List.of());
            boolean layersDiffer = fifo && !sameLayers(nowLayers, replayed.layers());
            if (!BalanceReplay.same(now, replayed) || layersDiffer) {
                differences++;
                rebuilt.put(key, replayed);
                if (fifo) {
                    layers.save(key, nowLayers, replayed.layers());
                }
                log.atWarn()
                        .addKeyValue("variantId", key.variantId())
                        .addKeyValue("locationId", key.locationId())
                        .addKeyValue("stockState", key.stockState())
                        .log("Balance differed from the ledger and was rebuilt");
            }
        }
        balances.overwrite(rebuilt);
        Instant at = clock.instant();
        log.atInfo()
                .addKeyValue("balances", keys.size())
                .addKeyValue("differences", differences)
                .log("Stock balances rebuilt");
        return new Result(keys.size(), differences, at);
    }

    private static boolean sameLayers(List<CostLayer> a, List<CostLayer> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            CostLayer x = a.get(i);
            CostLayer y = b.get(i);
            if (!x.id().equals(y.id())
                    || x.qtyRemaining().compareTo(y.qtyRemaining()) != 0
                    || x.valueRemaining().compareTo(y.valueRemaining()) != 0) {
                return false;
            }
        }
        return true;
    }
}
